package example

import java.util.concurrent.Executors
import cats.effect._
import cats.implicits._
import doobie.quill.DoobieContext
import doobie.util.transactor.Transactor
import doobie._
import doobie.implicits._
import io.circe._
import io.circe.syntax._
import io.circe.literal._
import io.circe.generic.auto._
import io.getquill.{H2JdbcContext, Literal, SnakeCase, UpperCase}
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.implicits._

import scala.concurrent.ExecutionContext
import org.flywaydb.core.Flyway
import org.http4s.blaze.server.BlazeServerBuilder

case class Video(id: Int, name: String)

case class Tag(id: Int, name: String)

case class VideoTag(id: Int, videoId: Int, tagId: Int)

object MyMain extends IOApp {

  val ctx = new H2JdbcContext(UpperCase, "ctx")

  private val flyway = Flyway.configure.dataSource(ctx.dataSource).load

  println(s"Flyway result: ${flyway.migrate()}")

  val xa: Transactor[IO]= Transactor.fromDataSource[IO](ctx.dataSource,ExecutionContext.global)

  val dc = new DoobieContext.H2(UpperCase) // Literal naming scheme

  import dc._

  private implicit val videoInsertMeta = insertMeta[Video](_.id)
  private implicit val tagInsertMeta = insertMeta[Tag](_.id)
  private implicit val videotagInsertMeta = insertMeta[VideoTag](_.id)

  object TagsParamMatcher extends QueryParamDecoderMatcher[String]("tags")

  private val videos = HttpRoutes.of[IO] {
    case GET -> Root / "video" =>
      val vids=dc.run{
        query[Video]
      }.transact(xa)
      Ok(vids.map(_.asJson)) // .recoverWith{errorhandler} // made global
    case GET -> Root / "video" / IntVar(id)=>
      val vids=dc.run{
        query[Video].filter(_.id==lift(id))
      }.transact(xa)
      Ok(vids.map(_.asJson))
    case GET -> Root / "video" / "tags" / IntVar(id)=>
      val tags=dc.run{
        query[VideoTag].join(query[Tag]).on(_.tagId == _.id).filter(_._1.videoId == lift(id)).map(_._2)
      }.transact(xa)
      Ok(tags.map(_.asJson))
    case POST -> Root / "video" / "tags" / IntVar(id) :? TagsParamMatcher(tags) =>

      val tagids=tags.split(",").map(Integer.parseInt).toList
      val videotags =tagids.map(i=>VideoTag(0,id,i))

      val transaction= for {
        _ <- dc.run(query[VideoTag].filter(_.videoId == lift(id)).delete)
        _ <- dc.run(liftQuery(videotags).foreach { vt =>
          query[VideoTag].insert(vt)
        })
      } yield ()

      val ids=transaction.transact(xa)
      Ok(ids.map(_.asJson))
    case POST -> Root / "video" / name =>
      val id=dc.run {
        query[Video].insert(lift(Video(0,name))).returningGenerated(_.id)
      }.transact(xa)
      Ok(id.map(_.asJson))
  }

  private val tags = HttpRoutes.of[IO] {
    case GET -> Root / "tag" =>
      val tags=dc.run{
        query[Tag]
      }.transact(xa)
      Ok(tags.map(_.asJson))
    case GET -> Root / "tag" / IntVar(id)=>
      val tags=dc.run{
        query[Tag].filter(_.id==lift(id))
      }.transact(xa)
      Ok(tags.map(_.asJson))
    case POST -> Root / "tag" / name =>
      val id=dc.run {
        query[Tag].insert(lift(Tag(0,name))).returningGenerated(_.id)
      }.transact(xa)
      Ok(id.map(_.asJson))
  }

  val errorhandler: PartialFunction[Throwable, IO[Response[IO]]] ={
    case th: Throwable=>
      th.printStackTrace()
      InternalServerError(s"InternalServerError: $th")
  }

  private val videotags = HttpRoutes.of[IO] {
    case GET -> Root / "videotag" =>
      val tags=dc.run{
        query[VideoTag]
      }.transact(xa)
      Ok(tags.map(_.asJson))
    case GET -> Root / "videotag" / IntVar(id)=>
      val videotags=dc.run{
        query[VideoTag].filter(_.id==lift(id))
      }.transact(xa)
      Ok(videotags.map(_.asJson))
    case POST -> Root / "videotag" / IntVar(videoId) / IntVar(tagId) =>
      val id=dc.run {
        query[VideoTag].insert(lift(VideoTag(0,videoId, tagId))).returningGenerated(_.id)
      }.transact(xa)
      Ok(id.map(_.asJson))
  }

  private val httpApp=(videos <+> tags <+> videotags).orNotFound

  def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .bindHttp(8081, "localhost")
    .withServiceErrorHandler(_=>errorhandler)
      .withHttpApp(httpApp)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)

}
