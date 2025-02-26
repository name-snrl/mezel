package mezel

import scala.concurrent.duration._
import fs2.io.file._
import fs2.Chunk
import cats.effect._
import cats.effect.std._
import fs2.concurrent.SignallingRef
import fs2.concurrent.Channel
import io.circe._
import io.circe.syntax._
import cats.implicits._
import catcheffect.Catch
import catcheffect.Raise
import java.nio.charset.StandardCharsets

class BSPServerLifecycle(
    buildArgs: List[String],
    aqueryArgs: List[String],
    deps: BSPServerDeps
) {
  def logger(originId: Option[String]): Logger =
    Logger.make(None, originId)(x => deps.output.send(x.asJson).void)

  val fromMetals = deps.tmpDir / "metals-to-mezel"
  val toMetals = deps.tmpDir / "mezel-to-metals"

  def makeOps(trace: mezel.Trace, logger: Logger): Raise[IO, BspResponseError] ?=> BspServerOps = R ?=>
    new BspServerOps(
      deps.state,
      deps.sup,
      deps.output,
      buildArgs,
      aqueryArgs,
      logger,
      trace,
      deps.cache,
      deps.cacheKeys
    )

  def runRequest(id: Option[RpcId])(res: IO[Either[BspResponseError, Option[Json]]]): IO[Unit] = {
    val handleError: IO[Option[Response]] =
      (res <&
        IO.sleep(
          /* metals seems to sometimes deadlock if mezel responds too fast (noop operations), maybe take a look at the code (lsp4j?) */
          200.millis
        )).map {
        case Left(err)    => Some(Response("2.0", id, None, Some(err.responseError)))
        case Right(value) =>
          // if id is defined always respond
          // if id is not defined only respond if value is defined
          id match {
            case Some(id) => Some(Response("2.0", Some(id), value, None))
            case None     => value.map(j => Response("2.0", None, Some(j), None))
          }
      }

    val leased = id.map(id => deps.rl.run(id, handleError).map(_.flatten)).getOrElse(handleError)

    leased.flatMap(_.map(_.asJson).traverse_(deps.output.send))
  }

  def read(stdin: fs2.Stream[IO, Byte]) =
    stdin
      .observe(Files[IO].writeAll(fromMetals))
      .through(jsonRpcRequests)

  def write(stdout: fs2.Pipe[IO, Byte, Unit]): fs2.Pipe[IO, Json, Unit] =
    _.map(_.deepDropNullValues.noSpaces)
      .map { data =>
        val encodedData = data.getBytes(StandardCharsets.UTF_8)
        // UTF-8 length != string length (special characters like gamma are encoded with more bytes)
        Chunk.array(s"Content-Length: ${encodedData.length}\r\n\r\n".getBytes(StandardCharsets.UTF_8)) ++
          Chunk.array(encodedData)
      }
      .unchunks
      .observe(Files[IO].writeAll(toMetals))
      .through(stdout)

  def handleRequest(x: Request)(implicit Exit: Raise[IO, Unit]) = {
    val originId = x.params.flatMap(_.asObject).flatMap(_.apply("originId")).flatMap(_.asString)

    def expect[A: Decoder]: IO[A] =
      IO.fromOption(x.params)(new RuntimeException(s"No params for method ${x.method}"))
        .map(_.as[A])
        .rethrow

    val lg = logger(originId)
    val trace = Trace.in(x.method, lg)

    runRequest(x.id) {
      deps.C.use[BspResponseError] { implicit R =>
        val ops = makeOps(trace, lg)
        trace.trace("root") {
          x.method match {
            case "build/initialize"       => expect[InitializeBuildParams].flatMap(ops.initalize)
            case "build/initialized"      => IO.pure(None)
            case "workspace/buildTargets" => ops.buildTargets
            case "buildTarget/scalacOptions" =>
              expect[ScalacOptionsParams].flatMap(p => ops.scalacOptions(p.targets.map(_.uri)))
            case "buildTarget/javacOptions" => IO.pure(Some(ScalacOptionsResult(Nil).asJson))
            case "buildTarget/sources" =>
              expect[SourcesParams].flatMap(sps => ops.sources(sps.targets.map(_.uri)))
            case "buildTarget/dependencySources" =>
              expect[DependencySourcesParams].flatMap(dsp => ops.dependencySources(dsp.targets.map(_.uri)))
            case "buildTarget/scalaMainClasses" =>
              IO.pure(Some(ScalaMainClassesResult(Nil, None).asJson))
            case "buildTarget/jvmRunEnvironment" =>
              IO.pure(Some(JvmRunEnvironmentResult(Nil).asJson))
            case "buildTarget/scalaTestClasses" =>
              IO.pure(Some(ScalaTestClassesResult(Nil).asJson))
            case "buildTarget/compile" =>
              expect[CompileParams].flatMap(p => ops.compile(p.targets.map(_.uri)))
            case "buildTarget/resources" =>
              expect[ResourcesParams]
                .map(p => Some(ResourcesResult(p.targets.map(t => ResourcesItem(t, Nil))).asJson))
            // case "workspace/reload" =>
            // state.getAndSet(BspState.empty).flatMap { os =>
            //   os.initReq.traverse(ops.initalize) >> ops.buildTargets.as(None)
            // }
            case "build/exit" | "build/shutdown" => Exit.raise(())
            case "$/cancelRequest" =>
              expect[CancelParams].flatMap(p => deps.rl.cancel(p.id)).as(None)
            case m => IO.raiseError(new RuntimeException(s"Unknown method: $m"))
          }
        }
      }
    }
  }

  def start(
      stdin: fs2.Stream[IO, Byte],
      stdout: fs2.Pipe[IO, Byte, Unit]
  ): IO[Unit] = {
    deps.C
      .use[Unit] { implicit Exit =>
        val consume =
          read(stdin).parEvalMapUnbounded { req =>
            handleRequest(req)
          }

        logger(None).logInfo(s"Starting Mezel server, logs will be at ${deps.tmpDir}") >>
          (deps.output.stream.concurrently(consume)).through(write(stdout)).compile.drain
      }
      .void
  }
}

final case class BSPServerDeps(
    tmpDir: Path,
    state: SignallingRef[IO, BspState],
    sup: Supervisor[IO],
    cache: Cache,
    cacheKeys: BspCacheKeys,
    output: Channel[IO, Json],
    rl: RequestLifecycle,
    C: Catch[IO]
)

object BSPServerDeps {
  def make =
    (
      Resource.eval(Files[IO].createTempDirectory(None, "mezel-logs-", None)),
      Resource.eval(SignallingRef.of[IO, BspState](BspState.empty)),
      Supervisor[IO](await = false),
      Resource.eval(Cache.make),
      Resource.eval(BspCacheKeys.make),
      Resource.eval(Channel.bounded[IO, Json](64)),
      Resource.eval(RequestLifecycle.make),
      Resource.eval(Catch.ioCatch)
    ).mapN(BSPServerDeps.apply)
}
