package `06libuvHttp`

import scalanative.unsigned.UnsignedRichInt
import scalanative.unsafe.*
import scalanative.libc.*
import scalanative.unsigned.*
import stdio.*
import stdlib.*
import string.*
import collection.mutable

case class HeaderLine(
    key: CString,
    value: CString,
    keyLen: UShort,
    valueLen: UShort
)

case class HttpRequest(
    method: String,
    uri: String,
    headers: collection.Map[String, String],
    body: String
)

case class HttpResponse(
    code: Int,
    headers: collection.Map[String, String],
    body: String
)

object HTTP:
  // import LibUV.*
  // import LibUVConstants.*

  type RequestHandler = Function1[HttpRequest, HttpResponse]
  type Buffer = CStruct2[Ptr[Byte], CSize]

  val HEADER_COMPLETE_NO_BODY = 0
  val HEADERS_INCOMPLETE = -1

  val MAX_URI_SIZE = 2048
  val MAX_METHOD_SIZE = 8

  val methodBuffer = malloc(16.toULong)
  val uriBuffer = malloc(4096.toULong)

  def scanRequestLine(line: CString): (String, String, Int) =
    val lineLen = stackalloc[Int](sizeof[Int])
    val scanResult = stdio.sscanf(
      line,
      c"%s %s %*s\r\n%n",
      methodBuffer,
      uriBuffer,
      lineLen
    )
    if scanResult == 2 then
      (fromCString(methodBuffer), fromCString(uriBuffer), !lineLen)
    else throw new Exception("bad request line")

  def scanHeaderLine(
      line: CString,
      outMap: mutable.Map[String, String],
      keyEnd: Ptr[Int],
      valueStart: Ptr[Int],
      valueEnd: Ptr[Int],
      lineLen: Ptr[Int]
  ): Int =
    !lineLen = -1
    val scanResult = stdio.sscanf(
      line,
      c"%*[^\r\n:]%n: %n%*[^\r\n]%n%*[\r\n]%n",
      keyEnd,
      valueStart,
      valueEnd,
      lineLen
    )
    if !lineLen != -1 then
      val startOfKey = line
      val endOfKey = line + !keyEnd
      !endOfKey = 0
      val startOfValue = line + !valueStart
      val endOfValue = line + !valueEnd
      !endOfValue = 0
      val key = fromCString(startOfKey)
      val value = fromCString(startOfValue)
      outMap(key) = value
      !lineLen
    else throw new Exception("bad header line")

  val lineBuffer = malloc(1024.toULong)

  def parseRequest(req: CString, size: Long): HttpRequest =
    // req(size) = 0 // ensure null termination
    var reqPosition = req
    val lineLen = stackalloc[Int](sizeof[Int])
    val keyEnd = stackalloc[Int](sizeof[Int])
    val valueStart = stackalloc[Int](sizeof[Int])
    val valueEnd = stackalloc[Int](sizeof[Int])
    val headers = mutable.Map[String, String]()

    val (method, uri, requestLen) = scanRequestLine(req)

    var bytesRead = requestLen

    while bytesRead < size
    do
      reqPosition = req + bytesRead
      val parseHeaderResult = scanHeaderLine(
        reqPosition,
        headers,
        keyEnd,
        valueStart,
        valueEnd,
        lineLen
      )
      if parseHeaderResult < 0 then throw new Exception("HEADERS INCOMPLETE")
      else if !lineLen - !valueEnd == 2 then bytesRead += parseHeaderResult
      else if !lineLen - !valueEnd == 4 then
        val remaining = size - bytesRead
        val body = fromCString(req + bytesRead)
        return HttpRequest(method, uri, headers, body)
      else throw new Exception("malformed header!")

    throw new Exception(s"bad scan, exceeded $size bytes")

  val keyBuffer = malloc(512.toULong)
  val valueBuffer = malloc(512.toULong)
  val bodyBuffer = malloc(4096.toULong)

  def makeResponse(response: HttpResponse, buffer: Ptr[Buffer]): Unit =
    stdio.snprintf(buffer._1, 4096.toULong, c"HTTP/1.1 200 OK\r\n")
    var headerPos = 0
    val bufferStart = buffer._1
    var bytesWritten = strlen(bufferStart)
    var lastPosition = bufferStart + bytesWritten
    var bytesRemaining = 4096.toULong - bytesWritten
    val headers = response.headers.keys.toSeq
    while headerPos < response.headers.size
    do
      val k = headers(headerPos)
      val v = response.headers(k)
      Zone { implicit z =>
        val keyTemp = toCString(k)
        val valueTemp = toCString(v)
        strncpy(keyBuffer, keyTemp, 512.toULong)
        strncpy(valueBuffer, valueTemp, 512.toULong)
      }

      stdio.snprintf(
        lastPosition,
        bytesRemaining.toULong,
        c"%s: %s\r\n",
        keyBuffer,
        valueBuffer
      )

      val len = strlen(lastPosition)
      bytesWritten = bytesWritten + len.toULong + 1.toULong
      bytesRemaining = 4096.toULong - bytesWritten
      lastPosition = lastPosition + len
      headerPos += 1

    Zone { implicit z =>
      val body = toCString(response.body)
      val bodyLen = strlen(body)
      strncpy(bodyBuffer, body, 4096.toULong)
    }
    stdio.snprintf(lastPosition, bytesRemaining, c"\r\n%s", bodyBuffer)
