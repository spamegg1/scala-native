/***
 * Excerpted from "Modern Systems Programming with Scala Native",
 * published by The Pragmatic Bookshelf.
 * Copyrights apply to this code. It may not be used to create training material,
 * courses, books, articles, and the like. Contact us if you are in doubt.
 * We make no guarantees that this code is fit for any purpose.
 * Visit http://www.pragmaticprogrammer.com/titles/rwscala for more book information.
***/
import scala.scalanative.unsafe._
import scala.scalanative.libc._
import collection.mutable
import scala.util.{Try,Success,Failure}
import scala.concurrent.{Future,ExecutionContext}
import scala.concurrent.{Promise}

trait Pipe[T,U] {
  val handlers = mutable.Set[Pipe[U,_]]()

  def feed(input:T):Unit
  def done():Unit = {
    for (h <- handlers) {
      h.done()
    }
  }

  def addDestination[V](dest:Pipe[U,V]):Pipe[U,V] = {
    handlers += dest
    dest
  }

  case class SyncPipe[T,U](f:T => U) extends Pipe[T,U] {
    override def feed(input:T):Unit = {
      val output = f(input)
      for (h <- handlers) {
        h.feed(output)
      }
    }
  }

  def map[V](g:U => V):Pipe[U,V] = {
    addDestination(SyncPipe(g))
  }

  case class ConcatPipe[T,U](f:T => Seq[U]) extends Pipe[T,U] {
    override def feed(input:T):Unit = {
      val output = f(input)
      for (h <- handlers;
           o <- output) {
        h.feed(o)
      }
    }
  }

  def mapConcat[V](g:U => Seq[V]):Pipe[U,V] = {
    addDestination(ConcatPipe(g))
  }

  case class OptionPipe[T,U](f:T => Option[U]) extends Pipe[T,U] {
    override def feed(input:T):Unit = {
      val output = f(input)
      for (h <- handlers;
           o <- output) {
        h.feed(o)
      }
    }
  }

  def mapOption[V](g:U => Option[V]):Pipe[U,V] = {
    addDestination(OptionPipe(g))
  }

  case class AsyncPipe[T,U](f:T => Future[U])
    (implicit ec:ExecutionContext) extends Pipe[T,U] {

    override def feed(input:T):Unit = {
      f(input).map { o =>
        for (h <- handlers) {
          h.feed(o)
        }
      }
    }
  }

  def mapAsync[V](g:U => Future[V])(implicit ec:ExecutionContext):Pipe[U,V] = {
    addDestination(AsyncPipe(g))
  }

  def onComplete(implicit ec:ExecutionContext):Future[Unit] = {
    val sink = OnComplete[U]()
    addDestination(sink)
    return sink.promise.future
  }
}

case class OnComplete[T]()(implicit ec:ExecutionContext) extends Pipe[T,Unit] {
  val promise = Promise[Unit]()
  override def feed(input:T) = {}

  override def done() = {
    println("done, completing promise")
    promise.success(())
  }
}

case class CounterSink[T]() extends Pipe[T,Nothing] {
  var counter = 0
  override def feed(input:T) = {
    counter += 1
  }
}

case class Tokenizer(separator:String) extends Pipe[String,String] {
  var buffer = " "

  def scan(input:String):Seq[String] = {
      println(s"scanning: '$input'")
      buffer = buffer + input
      var o:Seq[String] = Seq()
      while (buffer.contains(separator)) {
        val space_position = buffer.indexOf(separator)
        val word = buffer.substring(0,space_position)
        o = o :+ word
        buffer = buffer.substring(space_position + 1)
      }
      o

  }
  override def feed(input:String):Unit = {
    for (h    <- handlers;
         word <- scan(input)) {
           h.feed(word)
    }
  }

  override def done():Unit = {
    println(s"done!  current buffer: $buffer")
    for (h <- handlers) {
           h.feed(buffer)
           h.done()
    }
  }
}

case class FoldPipe[I,O](init:O)(f:(O,I) => O) extends Pipe[I,O] {
  var accum = init

  override def feed(input:I):Unit = {
    accum = f(accum,input)
    for (h <- handlers) {
      h.feed(accum)
    }
  }

  override def done():Unit = {
    for (h <- handlers) {
      h.done()
    }
  }
}

object Pipe {
  case class PipeSource[I]() extends Pipe[I,I] {
    override def feed(input:I):Unit = {
      for (h <- handlers) {
        h.feed(input)
      }
    }
  }
  def source[I]:Pipe[I,I] = {
    PipeSource[I]()
  }
}

// object FileOutPipe extends LoopExtension {
//   import LibUV._, LibUVConstants._

// }

object FilePipe {
  import LibUV._, LibUVConstants._
  type FilePipeState = CStruct3[Int,Ptr[Buffer],Long] // fd, buffer, offset

  var active_streams:mutable.Set[Int] = mutable.Set()
  var handlers = mutable.HashMap[Int,Pipe[String,String]]()
  var serial = 0

  def stream(path:CString):Pipe[String,String] = {
    val req = stdlib.malloc(uv_req_size(UV_FS_REQ_T)).asInstanceOf[FSReq]
    // check(uv_fs_open(EventLoop.loop,req,path,0,0,null),"uv_fs_open")
    println("opening file")
    val fd = util.open(path,0,0)
    stdio.printf(c"open file at %s returned %d\n", path, fd)

    // val fd2 = util.open(c"./test2",0,0)
    // stdio.printf(c"open file at %s returned %d\n", c"./test2", fd2)
    val state = stdlib.malloc(sizeof[FilePipeState]).asInstanceOf[Ptr[FilePipeState]]
    val buf = stdlib.malloc(sizeof[Buffer]).asInstanceOf[Ptr[Buffer]]
    buf._1 = stdlib.malloc(4096)
    buf._2 = 4095
    state._1 = fd
    state._2 = buf
    state._3 = 0L
    !req = state.asInstanceOf[Ptr[Byte]]

    println("about to read")
    uv_fs_read(EventLoop.loop,req,fd,buf,1,-1,readCB)
    println("read started")
    val pipe = Pipe.source[String]
    handlers(fd) = pipe
    println("about to return")
    active_streams += fd
    pipe
  }

  val readCB:FSCB = new FSCB {
    def apply(req:FSReq):Unit = {
      println("read callback fired!")
      val res = uv_fs_get_result(req)
      println(s"got result: $res")
      val state_ptr = (!req).asInstanceOf[Ptr[FilePipeState]]
      println("inspecting state")
      val fd = state_ptr._1
      val buf = state_ptr._2
      val offset = state_ptr._3
      printf("state: fd %d, offset %d\n", fd, offset.toInt)

      if (res > 0) {
        println("producing string")
        (buf._1)(res) = 0
        val output = fromCString(buf._1)
        val pipe = handlers(fd)
        pipe.feed(output)
        println("continuing")
        state_ptr._3 = state_ptr._3 + res
        uv_fs_read(EventLoop.loop,req,fd,state_ptr._2,1,state_ptr._3,readCB)
      } else if (res == 0) {
        println("done")
        val pipe = handlers(fd)
        pipe.done()
        active_streams -= fd
      } else {
        println("error")
        active_streams -= fd
      }
    }
  }
}

object SyncPipe {
  import LibUV._, LibUVConstants._

  var active_streams:mutable.Set[Int] = mutable.Set()
  var handlers = mutable.HashMap[Int,Pipe[String,String]]()
  var serial = 0

  def activeRequests:Int = {
    active_streams.size
  }

  def stream(fd:Int):Pipe[String,String] = {
    val handle = stdlib.malloc(uv_handle_size(UV_PIPE_T))
    uv_pipe_init(EventLoop.loop,handle,0)
    val pipe_data = handle.asInstanceOf[Ptr[Int]]
    !pipe_data = serial
    active_streams += serial
    val pipe = Pipe.source[String]
    handlers(serial) = pipe

    serial += 1
    uv_pipe_open(handle,fd)
    uv_read_start(handle,allocCB,readCB)
    pipe
  }

  val allocCB = new AllocCB {
    def apply(client:PipeHandle, size:CSize, buffer:Ptr[Buffer]):Unit = {
      val buf = stdlib.malloc(4096)
      buffer._1 = buf
      buffer._2 = 4096
    }
  }

  val readCB = new ReadCB {
    def apply(handle:PipeHandle,size:CSize,buffer:Ptr[Buffer]):Unit = {
      val pipe_data = handle.asInstanceOf[Ptr[Int]]
      val pipe_id = !pipe_data
      println(s"read $size bytes from pipe $pipe_id")
      if (size < 0) {
        println("size < 0, closing")
        active_streams -= pipe_id
        val pipe_destination = handlers(pipe_id)
        pipe_destination.done()
        handlers.remove(pipe_id)
      } else {
        val data_buffer = stdlib.malloc(size + 1)
        string.strncpy(data_buffer, buffer._1, size + 1)
        val data_string = fromCString(data_buffer)
        stdlib.free(data_buffer)
        val pipe_destination = handlers(pipe_id)
        pipe_destination.feed(data_string.trim())
      }
    }
  }
}

object Main {
  import LibUV._, LibUVConstants._
  implicit val ec = EventLoop
  def main(args:Array[String]):Unit = {
    println("hello!")
    val p = FilePipe.stream(c"./data.text")
    println("ok")
    var buffer = ""
    val done = p.map { d =>
      println(s"consumed $d")
      d
    }.addDestination(Tokenizer("\n"))
    .addDestination(Tokenizer(" "))
    .map { word =>
      println(s"word: $word")
    }.onComplete.map { _ =>
      println("stream completed!")
    }
    println("running")
    uv_run(EventLoop.loop,UV_RUN_DEFAULT)
  }
}

@extern
object util {
  def open(path:CString, flags:Int, mode:Int):Int = extern
}
