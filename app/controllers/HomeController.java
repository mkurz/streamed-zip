package controllers;

import akka.stream.javadsl.Source;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

public class HomeController extends Controller {

    public Result index() {
        final List<ZipToStreamFlow.ZipSource> filesToZip = new ArrayList<>();

        filesToZip.add(new ZipToStreamFlow.ZipSource("myfile1.txt", fileAsLazyInputStream("/tmp/file1.txt")));
        filesToZip.add(new ZipToStreamFlow.ZipSource("myfile2.txt", fileAsLazyInputStream("/tmp/file2.txt")));
        filesToZip.add(new ZipToStreamFlow.ZipSource("subfolder/myfile3.txt", fileAsLazyInputStream("/tmp/file3.txt")));
        // For s3 you have to do something like this:
        //filesToZip.add(new ZipToStreamFlow.ZipSource("file_from_s3.txt", (scala.Function0) () -> s3.getInputStream(storagePath)));

        return ok()
                .chunked(Source.from(filesToZip).via(new ZipToStreamFlow(64 * 1024))/*.mapMaterializedValue(val -> code hier für cleanup) */) // also try e.g. 8192 as bufferSize
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
