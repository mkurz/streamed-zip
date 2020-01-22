package controllers;

import akka.stream.IOResult;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.StreamConverters;
import akka.util.ByteString;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import play.libs.F;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class HomeController extends Controller {

    public Result index() {
        final List<ZipToStreamFlow.ZipSource> filesToZip = new ArrayList<>();

        filesToZip.add(new ZipToStreamFlow.ZipSource("myfile1.txt", fileAsLazyInputStream("/tmp/file1.txt")));
        filesToZip.add(new ZipToStreamFlow.ZipSource("myfile2.txt", fileAsLazyInputStream("/tmp/file2.txt")));
        filesToZip.add(new ZipToStreamFlow.ZipSource("subfolder/myfile3.txt", fileAsLazyInputStream("/tmp/file3.txt")));

        // https://stackoverflow.com/questions/5778658/how-to-convert-outputstream-to-inputstream
        // https://docs.oracle.com/javase/7/docs/api/java/io/PipedInputStream.html
        filesToZip.add(new ZipToStreamFlow.ZipSource("org.pdf", (scala.Function0) () -> {
            F.Tuple<PDFMergerUtility, PipedInputStream> mergepdf = null;
            try {
                mergepdf = mergepdf();
            } catch (IOException e) {
                e.printStackTrace();
            }
            F.Tuple<PDFMergerUtility, PipedInputStream> final_mergepdf = mergepdf;
            try {
                return mergepdf._2;
            } finally {
                CompletableFuture.runAsync(() ->
                        {
                            try {
                                final_mergepdf._1.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                );
            }
        }));
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

    // https://stackoverflow.com/questions/3585329/how-to-merge-two-pdf-files-into-one-in-java
    private static F.Tuple<PDFMergerUtility, PipedInputStream> mergepdf() throws IOException {
        final PDFMergerUtility ut = new PDFMergerUtility();
        ut.setDocumentMergeMode(PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE);
        try {
            ut.addSource("/tmp/1.pdf");
            ut.addSource("/tmp/2.pdf");
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
        }
        //ut.setDestinationFileName("/tmp/finalle.pdf");

        //BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(new File("/tmp/org.pdf")));
        //ut.setDestinationStream(bufferedOutputStream);

        PipedInputStream in = new PipedInputStream();
        final PipedOutputStream out = new PipedOutputStream(in);
        ut.setDestinationStream(out);

        MemoryUsageSetting mus = MemoryUsageSetting.setupTempFileOnly(1024*260); // MemoryUsageSetting.setupMainMemoryOnly(1024*272); //MemoryUsageSetting.setupMixed(50, 1024*260);
        //mus.setTempDir(...)
        System.out.println("Tempfolder: " + mus.getTempDir());
        System.out.println(mus.toString());
        //ut.mergeDocuments(mus);
        return F.Tuple(ut, in);
    }

}
