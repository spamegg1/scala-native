/***
 * Excerpted from "Modern Systems Programming with Scala Native",
 * published by The Pragmatic Bookshelf.
 * Copyrights apply to this code. It may not be used to create training material,
 * courses, books, articles, and the like. Contact us if you are in doubt.
 * We make no guarantees that this code is fit for any purpose.
 * Visit http://www.pragmaticprogrammer.com/titles/rwscala for more book information.
***/
@extern object Poll {
  type PollHandle = Ptr[Ptr[Byte]]
  type PollCB = CFunctionPtr3[PollHandle, Int, Int, Unit]

  def uv_poll_init_socket(loop:Loop, handle:PollHandle, socket:Ptr[Byte]):Int = extern
  def uv_poll_start(handle:PollHandle, events:Int, cb: PollCB):Int = extern
  def uv_poll_stop(handle:PollHandle):Int = extern
}