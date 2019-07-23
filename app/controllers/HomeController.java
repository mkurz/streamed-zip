package controllers;

import akka.stream.javadsl.Source;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import scala.Function0;
import scala.Tuple2;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class HomeController extends Controller {

    public Result index() {
        final List<Tuple2<String, Function0<InputStream>>> filesToZip = new ArrayList<>();

        filesToZip.add(Tuple2.apply("myfile1.txt", fileAsLazyInputStream("/tmp/file1.txt")));
        filesToZip.add(Tuple2.apply("myfile2.txt", fileAsLazyInputStream("/tmp/file2.txt")));
        filesToZip.add(Tuple2.apply("subfolder/myfile3.txt", fileAsLazyInputStream("/tmp/file3.txt")));
        // For s3 you have to do something like this:
        //filesToZip.add(Tuple2.apply("file_from_s3.txt", (scala.Function0) () -> s3.getInputStream(storagePath)));

        return ok()
                .chunked(Source.from(filesToZip).via(StreamedZip$.MODULE$.apply())) // or: new StreamedZip(64 * 1024) to set bufferSize
                .as("application/zip")
                .withHeaders(Http.HeaderNames.CONTENT_DISPOSITION, "attachment; filename=backup.zip");
    }

    private static scala.Function0 fileAsLazyInputStream(final String path) {
        return () -> {
            try {
                return new FileInputStream(path);
            } catch (final FileNotFoundException e) {
                throw new RuntimeException("Can not add non-existing file to zip archive!", e);
            }
        };
    }

}
