package demo.reactivestreams.part5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FileModificationRunner {

    public static void main(String[] args) throws InterruptedException, IOException {
        Path file = Paths.get(System.getProperty("user.home") + "/example.txt");

        TimeUnit.SECONDS.sleep(10);
        Files.createFile(file);

        TimeUnit.SECONDS.sleep(10);
        Files.write(file, List.of("text"), StandardCharsets.UTF_8);

        TimeUnit.SECONDS.sleep(10);
        Files.delete(file);
    }
}
