package ai.sapper.hcdc.utils;

import ai.sapper.hcdc.common.ConfigReader;
import ai.sapper.hcdc.common.model.services.EConfigFileType;
import ai.sapper.hcdc.common.utils.DefaultLogger;
import ai.sapper.hcdc.core.connections.ConnectionManager;
import ai.sapper.hcdc.core.connections.HdfsConnection;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import javax.naming.ConfigurationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HadoopDataLoader {
    @Parameter(names = {"--config", "-c"}, description = "Configuration File path", required = true)
    private String configfile;
    @Parameter(names = {"--input", "-i"}, description = "Input Data Format (CSV)", required = true)
    private String inputFormat;
    @Parameter(names = {"--data", "-d"}, description = "Directory path to load data from.", required = true)
    private String dataFolder;
    @Parameter(names = {"--output", "-o"}, description = "Output Data Format (Parquet, Avro)", required = true)
    private String outputFormat;
    @Parameter(names = {"--tmp", "-t"}, description = "Temp directory to use to create local files. [DEFAULT=System.getProperty(\"java.io.tmpdir\")]")
    private String tempDir = System.getProperty("java.io.tmpdir");
    @Parameter(names = {"--batchSize", "-b"}, description = "Batch Size to read input data. [Output files will also be limited to this batch size] [DEFAULT=8192]")
    private int readBatchSize = 1024 * 16;
    private HierarchicalConfiguration<ImmutableNode> config;
    private LoaderConfig loaderConfig;
    private ConnectionManager connectionManager;
    private HdfsConnection connection;
    private FileSystem fs;
    private Path basePath;

    public void run() throws Exception {
        try {
            config = ConfigReader.read(configfile, EConfigFileType.File);
            loaderConfig = new LoaderConfig(config);
            loaderConfig.read();

            connectionManager = new ConnectionManager();
            connectionManager.init(config, loaderConfig.connectionPath);

            connection = connectionManager.getConnection(loaderConfig.connectionToUse, HdfsConnection.class);
            connection.connect();

            fs = connection.fileSystem();

            basePath = new Path(loaderConfig.baseDir);
            if (!fs.exists(basePath)) {
                fs.mkdirs(basePath);
            }
            read(new File(dataFolder));

        } catch (Throwable t) {
            DefaultLogger.LOG.error(t.getLocalizedMessage());
            DefaultLogger.LOG.debug(DefaultLogger.stacktrace(t));
            throw new Exception(t);
        }
    }

    private void read(@NonNull File dir) throws Exception {
        Preconditions.checkArgument(dir.isDirectory());
        File[] files = dir.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isDirectory()) read(file);
                if (InputDataReader.EInputFormat.isValidFile(file.getName())) {
                    process(file);
                }
            }
        }
    }

    private void process(@NonNull File file) throws Exception {
        try (InputDataReader<List<String>> reader = getReader(file.getAbsolutePath())) {
            int index = 1;

            while (true) {
                List<List<String>> records = reader.read();
                if (records != null && !records.isEmpty()) {
                    String folder = getFolderName(file);
                    String datePath = OutputDataWriter.getDatePath();
                    OutputDataWriter.EOutputFormat f = OutputDataWriter.EOutputFormat.parse(outputFormat);
                    Preconditions.checkNotNull(f);
                    String dir = String.format("%s/%s/%s", basePath, folder, datePath);
                    String tdir = String.format("%s/%s", tempDir, dir);

                    File td = new File(tdir);
                    if (!td.exists()) {
                        td.mkdirs();
                    }
                    DefaultLogger.LOG.debug(String.format("Writing local files to [%s]", td.getAbsolutePath()));
                    int arrayIndex = 0;
                    OutputDataWriter<List<String>> writer = null;
                    while (true) {
                        String filename = String.format("%s_%d.%s", folder, index, f.name().toLowerCase());
                        writer = getWriter(tdir, filename);
                        Preconditions.checkNotNull(writer);
                        try {
                            List<List<String>> batch = nextBatch(records, arrayIndex);
                            if (batch == null) break;
                            writer.write(folder, reader.header(), batch);

                            upload(String.format("%s/%s", td.getAbsolutePath(), filename), dir, filename);

                            arrayIndex += batch.size();
                            index++;
                        } finally {
                            writer.close();
                        }
                    }
                } else break;
            }
        }
    }

    private void upload(String source, String dir, String filename) throws IOException {
        Path path = new Path(String.format("%s/%s", dir, filename));
        try (FSDataOutputStream fsDataOutputStream = fs.create(path, true)) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fsDataOutputStream, StandardCharsets.UTF_8))) {
                File file = new File(source);    //creates a new file instance
                FileReader fr = new FileReader(file);   //reads the file
                try (BufferedReader reader = new BufferedReader(fr)) {  //creates a buffering character input stream
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
        }
    }

    private List<List<String>> nextBatch(List<List<String>> records, int startIndex) {
        if (startIndex >= records.size()) return null;

        List<List<String>> batch = new ArrayList<>();
        long size = 0;
        for (int ii = startIndex; ii < records.size(); ii++) {
            long rsize = 0;
            List<String> record = records.get(ii);
            for (String r : record) {
                rsize += r.length();
            }
            if (size + rsize > loaderConfig.batchSize) break;
            batch.add(record);
            size += rsize;
        }
        return batch;
    }

    private String getFolderName(File file) {
        return FilenameUtils.removeExtension(file.getName());
    }

    private InputDataReader<List<String>> getReader(String filename) throws Exception {
        InputDataReader.EInputFormat f = InputDataReader.EInputFormat.parse(inputFormat);
        if (f == null) {
            throw new Exception(String.format("Invalid Input format type. [type=%s]", inputFormat));
        }
        switch (f) {
            case CSV:
                return new CSVDataReader(filename, ',').withBatchSize(readBatchSize);
        }
        throw new Exception(String.format("Input format not supported. [format=%s]", f.name()));
    }

    private OutputDataWriter<List<String>> getWriter(String dir, String filename) throws Exception {
        OutputDataWriter.EOutputFormat f = OutputDataWriter.EOutputFormat.parse(outputFormat);
        if (f == null) {
            throw new Exception(String.format("Invalid Output format type. [type=%s]", outputFormat));
        }
        switch (f) {
            case Parquet:
                return new ParquetDataWriter(dir, filename, fs);
        }
        throw new Exception(String.format("Output format not supported. [format=%s]", f.name()));
    }

    @Getter
    @Accessors(fluent = true)
    public static class LoaderConfig extends ConfigReader {
        private static final String __CONFIG_PATH = "loader";
        private static final String CONFIG_CONNECTIONS = "connections.path";
        private static final String CONFIG_CONNECTION_HDFS = "connections.use";
        private static final String CONFIG_BATCH_SIZE = "batchSize";
        private static final String CONFIG_BASE_DIR = "baseDir";

        private String connectionPath;
        private long batchSize = 1024 * 1024 * 16;
        private String baseDir;
        private String connectionToUse;

        public LoaderConfig(@NonNull HierarchicalConfiguration<ImmutableNode> config) {
            super(config, __CONFIG_PATH);
        }

        public void read() throws ConfigurationException {
            connectionPath = get().getString(CONFIG_CONNECTIONS);
            if (Strings.isNullOrEmpty(connectionPath)) {
                throw new ConfigurationException(String.format("HDFS Data Loader Configuration Error: missing [%s]", CONFIG_CONNECTIONS));
            }
            baseDir = get().getString(CONFIG_BASE_DIR);
            if (Strings.isNullOrEmpty(baseDir)) {
                throw new ConfigurationException(String.format("HDFS Data Loader Configuration Error: missing [%s]", CONFIG_BASE_DIR));
            }
            connectionToUse = get().getString(CONFIG_CONNECTION_HDFS);
            if (Strings.isNullOrEmpty(connectionToUse)) {
                throw new ConfigurationException(String.format("HDFS Data Loader Configuration Error: missing [%s]", CONFIG_CONNECTION_HDFS));
            }
            String s = get().getString(CONFIG_BATCH_SIZE);
            if (!Strings.isNullOrEmpty(s)) {
                batchSize = Long.parseLong(s);
            }
        }
    }

    public static void main(String[] args) {
        try {
            HadoopDataLoader loader = new HadoopDataLoader();
            JCommander.newBuilder().addObject(loader).build().parse(args);
            loader.run();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
