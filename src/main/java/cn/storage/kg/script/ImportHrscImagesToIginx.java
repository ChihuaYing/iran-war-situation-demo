package cn.storage.kg.script;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.session.SessionExecuteSqlResult;
import cn.edu.tsinghua.iginx.thrift.DataType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImportHrscImagesToIginx {
    private static final Pattern IMG_LOCATION =
            Pattern.compile("<Img_Location>\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*</Img_Location>");
    private static final Pattern PLACE_ID =
            Pattern.compile("<Place_ID>\\s*(\\d+)\\s*</Place_ID>");

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 6888;
    private static final String DEFAULT_USERNAME = "root";
    private static final String DEFAULT_PASSWORD = "root";
    private static final String DEFAULT_DATA_DIR = "D:/HRSC2016-master/FullDataSet";
    private static final int DEFAULT_THUMB_SIZE = 256;
    private static final Set<String> WEST_LONGITUDE_PLACE_IDS = new LinkedHashSet<String>(Arrays.asList(
            "100000002",
            "100000003",
            "100000004",
            "100000005",
            "100000006"
    ));
    private static final Set<String> DEFAULT_IMAGE_IDS = new LinkedHashSet<String>(Arrays.asList(
            "100000001",
            "100000650",
            "100000700",
            "100000770",
            "100001000",
            "100001300"
    ));

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        Session session = new Session(config.host, config.port, config.username, config.password);
        try {
            session.openSession();
            importImages(session, config);
        } finally {
            closeQuietly(session);
        }
    }

    private static void importImages(Session session, Config config) throws Exception {
//        executeSql(session, storageEngineSql(config));

        List<String> imageIds = new ArrayList<String>(config.imageIds);
        Collections.sort(imageIds);
        System.out.println("Import image ids: " + imageIds);

        int imported = 0;
        for (String imageId : imageIds) {
            String imageSql = "select `" + imageId + "\\.bmp` from `filesystem.FullDataSet.AllImages`;";
            String xmlSql = "select `" + imageId + "\\.xml` from `filesystem.FullDataSet.Annotations`;";
            byte[] imageBytes = concatBinaryColumn(session, imageSql);
            byte[] xmlBytes = concatBinaryColumn(session, xmlSql);
            if (imageBytes.length == 0 || xmlBytes.length == 0) {
                System.out.println("Skip " + imageId + ": empty image or xml.");
                continue;
            }

            double[] location = extractLocation(xmlBytes);
            byte[] thumbBytes = createThumbnail(imageBytes, config.thumbSize);
            long key = Long.parseLong(imageId);
            insertImage(session, key, imageBytes, location[0], location[1], thumbBytes);
            imported++;

            if (imported % 100 == 0) {
                System.out.println("Imported " + imported + " images...");
            }
        }

        System.out.println("Done. Imported " + imported + " images into sys.image.");
    }

    private static String storageEngineSql(Config config) {
        return "ADD STORAGEENGINE(\"127.0.0.1\", 6669, \"filesystem\", OPTIONS ("
                + "has_data \"true\", "
                + "is_read_only \"true\", "
                + "schema_prefix \"filesystem\", "
                + "dummy_dir \"" + escapeSqlString(config.dataDir) + "\", "
                + "iginx_port \"" + config.port + "\""
                + "));";
    }

    private static byte[] concatBinaryColumn(Session session, String sql) throws SessionException {
        SessionExecuteSqlResult result = executeSql(session, sql);
        printQueryShape(result);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (result.getValues() == null) {
            return output.toByteArray();
        }
        for (List<Object> row : result.getValues()) {
            if (row == null) {
                continue;
            }
            for (Object value : row) {
                byte[] bytes = toBytes(value);
                if (bytes != null) {
                    output.write(bytes, 0, bytes.length);
                }
            }
        }
        return output.toByteArray();
    }

    private static double[] extractLocation(byte[] xmlBytes) {
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        Matcher matcher = IMG_LOCATION.matcher(xml);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot find Img_Location in xml.");
        }
        String placeId = extractPlaceId(xml);
        double latitude = Double.parseDouble(matcher.group(1));
        double longitude = Double.parseDouble(matcher.group(2));
        if (WEST_LONGITUDE_PLACE_IDS.contains(placeId) && longitude > 0) {
            longitude = -longitude;
        }
        System.out.println(String.format(Locale.ROOT,
                "Extracted location: placeId=%s, latitude=%.6f, longitude=%.6f",
                placeId == null ? "unknown" : placeId, latitude, longitude));
        return new double[] {
                latitude,
                longitude
        };
    }

    private static String extractPlaceId(String xml) {
        Matcher matcher = PLACE_ID.matcher(xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static byte[] createThumbnail(byte[] imageBytes, int maxSize) throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) {
            throw new IllegalArgumentException("Cannot decode image bytes.");
        }

        double scale = Math.min(1.0d, Math.min((double) maxSize / source.getWidth(), (double) maxSize / source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(thumbnail, "jpg", output);
        return output.toByteArray();
    }

    private static void insertImage(Session session, long key, byte[] img, double latitude, double longitude, byte[] thumb)
            throws SessionException {
        List<String> paths = Arrays.asList("sys.image.img", "sys.image.latitude", "sys.image.longitude", "sys.image.thumb");
        List<DataType> dataTypes = Arrays.asList(DataType.BINARY, DataType.DOUBLE, DataType.DOUBLE, DataType.BINARY);
        Object[] values = new Object[] {
                new Object[] {img},
                new Object[] {latitude},
                new Object[] {longitude},
                new Object[] {thumb}
        };
        System.out.println(String.format(Locale.ROOT,
                "Insert into sys.image: key=%d, img=%d bytes, latitude=%.6f, longitude=%.6f, thumb=%d bytes",
                key, img.length, latitude, longitude, thumb.length));
        session.insertColumnRecords(paths, new long[] {key}, values, dataTypes);
    }

    private static SessionExecuteSqlResult executeSql(Session session, String sql) throws SessionException {
        System.out.println("Execute SQL: " + sql);
        return session.executeSql(sql);
    }

    private static void printQueryShape(SessionExecuteSqlResult result) {
        int keyCount = result.getKeys() == null ? 0 : result.getKeys().length;
        int rowCount = result.getValues() == null ? 0 : result.getValues().size();
        System.out.println("Query result: paths=" + result.getPaths()
                + ", dataTypes=" + result.getDataTypeList()
                + ", keys=" + keyCount
                + ", rows=" + rowCount);
    }

    private static byte[] toBytes(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof ByteBuffer) {
            ByteBuffer duplicate = ((ByteBuffer) value).duplicate();
            byte[] bytes = new byte[duplicate.remaining()];
            duplicate.get(bytes);
            return bytes;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeSqlString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void closeQuietly(Session session) {
        try {
            session.closeSession();
        } catch (Exception ignored) {
        }
    }

    private static class Config {
        private String host = DEFAULT_HOST;
        private int port = DEFAULT_PORT;
        private String username = DEFAULT_USERNAME;
        private String password = DEFAULT_PASSWORD;
        private String dataDir = DEFAULT_DATA_DIR;
        private int thumbSize = DEFAULT_THUMB_SIZE;
        private Set<String> imageIds = new LinkedHashSet<String>(DEFAULT_IMAGE_IDS);

        private static Config fromArgs(String[] args) {
            Config config = new Config();
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("host")) {
                config.host = options.get("host");
            }
            if (options.containsKey("port")) {
                config.port = Integer.parseInt(options.get("port"));
            }
            if (options.containsKey("username")) {
                config.username = options.get("username");
            }
            if (options.containsKey("password")) {
                config.password = options.get("password");
            }
            if (options.containsKey("dataDir")) {
                config.dataDir = options.get("dataDir");
            }
            if (options.containsKey("thumbSize")) {
                config.thumbSize = Integer.parseInt(options.get("thumbSize"));
            }
            if (options.containsKey("ids")) {
                config.imageIds = parseImageIds(options.get("ids"));
            }
            return config;
        }

        private static Set<String> parseImageIds(String value) {
            Set<String> ids = new LinkedHashSet<String>();
            for (String part : value.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            return ids;
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> options = new LinkedHashMap<String, String>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    continue;
                }
                int separator = arg.indexOf('=');
                String key = arg.substring(2, separator).trim();
                String value = arg.substring(separator + 1).trim();
                if (!key.isEmpty()) {
                    options.put(key, value);
                }
            }
            return options;
        }
    }
}
