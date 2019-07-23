/*------------------------------------------------------------------------------
 * MIT License
 * 
 * Copyright (c) 2017 Doug Kirk
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *----------------------------------------------------------------------------*/

package controllers

import akka.stream._
import akka.stream.stage.{GraphStage,
                          GraphStageLogic,
                          InHandler,
                          OutHandler,
                          StageLogging}
import akka.util.{ByteString, ByteStringBuilder}
import java.io.{InputStream, OutputStream}
import scala.util.control.NonFatal


/**
 * Streamed zip-file creation Akka streams implementation.
 * Typical usage, assuming an implicit ActorSystem:
 *
 *     import akka.stream._
 *     import akka.stream.scaladsl._
 *
 *     val filesToZip: Iterable[StreamedZip.ZipSource] = ...
 *     Source(filesToZip)
 *       .via(StreamedZip())
 *       .runWith(someSink)(ActorMaterializer())
 *
 * The ActorMaterializer can also be implicit.
 */
class StreamedZip(bufferSize: Int = 64 * 1024)
    extends GraphStage[FlowShape[StreamedZip.ZipSource, ByteString]] {

    import StreamedZip._
    
    val in: Inlet[ZipSource] = Inlet("StreamedZip.in")
    val out: Outlet[ByteString] = Outlet("StreamedZip.out")
    override val shape = FlowShape.of(in, out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape)
                                                                               with StageLogging {
    private val buffer = new ZipBuffer(bufferSize)
    private var currentStream: Option[InputStream] = None

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (isClosed(in)) {
          if (buffer.isEmpty) completeStage()
          else {
            buffer.close
            push(out, buffer.toByteString)
          }
        }
        else pull(in)
      }

      override def onDownstreamFinish(): Unit = {
        closeInput()
        buffer.close
        super.onDownstreamFinish()
      }
    })


    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val (filepath, source) = grab(in)
        buffer.startEntry(filepath)
        val stream = source()
        currentStream = Some(stream)
        emitMultiple(out, fileChunks(stream, buffer), () => {
          buffer.endEntry()
          closeInput()
        })
      }

      
      override def onUpstreamFinish(): Unit = {
        if (!buffer.isEmpty) {
          buffer.close()
          if (isAvailable(out)) {
            push(out, buffer.toByteString)
          }
        }
        else super.onUpstreamFinish()
      }
    })


    private def closeInput(): Unit = {
      currentStream.foreach(_.close)
      currentStream = None
    }


    private def fileChunks(stream: InputStream, buffer: ZipBuffer): Iterator[ByteString] = {
        // This seems like a good trade-off between single-byte
        // read I/O performance and doubling the ZipBuffer size.
        //
        // And it's still a decent defense against DDOS resource
        // limit attacks.
      val readBuffer = new Array[Byte](1024)
      var done = false

      def result: Stream[ByteString] = {
        if (done) Stream.empty
        else {
          try {
            while (!done && buffer.remaining > 0) {
              val bytesToRead = Math.min(readBuffer.length, buffer.remaining)
              val count = stream.read(readBuffer, 0, bytesToRead)
              if (count == -1) {
                stream.close
                done = true
              }
              else buffer.write(readBuffer, count)
            }
            buffer.toByteString #:: result
          }
          catch {
            case NonFatal(e) =>
              closeInput()
              throw e
          }
        }
      }

      result.iterator
    }
  }
}


object StreamedZip {
  type ZipFilePath = String
  type StreamGenerator = () => InputStream
  type ZipSource = (ZipFilePath, StreamGenerator)


  def apply() = new StreamedZip()

  trait SetZipStream {
    def setOut(out: OutputStream): Unit
  }


  private class ZipBuffer(val bufferSize: Int = 64 * 1024) {
    import java.util.zip.{ZipEntry, ZipOutputStream}

    private var builder = new ByteStringBuilder()
    private val zip = new ZipOutputStream(builder.asOutputStream) with SetZipStream {
        // this MUST ONLY be used after flush()!
      override def setOut(newStream: OutputStream): Unit = out = newStream
    }
    private var inEntry = false
    private var closed = false

    def close(): Unit = {
      endEntry()
      closed = true
      zip.close()
    }

    def remaining(): Int = bufferSize - builder.length

    def isEmpty(): Boolean = builder.isEmpty

    def startEntry(path: String): Unit =
      if (!closed) {
        endEntry()
        zip.putNextEntry(new ZipEntry(path))
        inEntry = true
      }

    def endEntry(): Unit =
      if (!closed && inEntry) {
        inEntry = false
        zip.closeEntry()
      }

    def write(byte: Int): Unit =
      if (!closed && inEntry) zip.write(byte)

    def write(bytes: Array[Byte], length: Int): Unit =
      if (!closed && inEntry) zip.write(bytes, 0, length)

    def toByteString(): ByteString = {
      zip.flush()
      val result = builder.result
      builder = new ByteStringBuilder()
        // set the underlying output for the zip stream to be the buffer
        // directly, so we don't have to copy the zip'd byte array.
      zip.setOut(builder.asOutputStream)
      result
    }
  }
}