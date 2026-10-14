import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;

public class BuildCar {
    static final String[][] KINDS = {{"endpoints", "synapse/endpoint"}, {"sequences", "synapse/sequence"}, {"apis", "synapse/api"}};
    static final String ROLE = "EnterpriseServiceBus";

    public static void main(String[] args) throws Exception {
        Path src = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        String app = args.length > 2 ? args[2] : "JamiiSavingsCompositeApp";
        String ver = args.length > 3 ? args[3] : "1.0.0";
        Files.createDirectories(outDir);
        Path car = outDir.resolve(app + "_" + ver + ".car");
        StringBuilder root = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<artifacts>\n  <artifact name=\"" + app + "\" version=\"" + ver + "\" type=\"carbon/application\">\n");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int count = 0;
        for (String[] kind : KINDS) {
            Path dir = src.resolve(kind[0]);
            if (!Files.isDirectory(dir)) continue;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.xml")) { ds.forEach(files::add); }
            Collections.sort(files);
            for (Path f : files) {
                String name = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f.toFile()).getDocumentElement().getAttribute("name");
                if (name.isEmpty()) throw new IllegalStateException(f + " has no name attribute");
                String folder = name + "_" + ver;
                entries.put(folder + "/artifact.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<artifact name=\"" + name + "\" version=\"" + ver + "\" type=\"" + kind[1] + "\" serverRole=\"" + ROLE + "\">\n  <file>" + f.getFileName() + "</file>\n</artifact>\n").getBytes(StandardCharsets.UTF_8));
                entries.put(folder + "/" + f.getFileName(), Files.readAllBytes(f));
                root.append("    <dependency artifact=\"" + name + "\" version=\"" + ver + "\" include=\"true\" serverRole=\"" + ROLE + "\"/>\n");
                count++;
                System.out.println("  + " + kind[1] + "  " + name);
            }
        }
        root.append("  </artifact>\n</artifacts>\n");
        if (count == 0) throw new IllegalStateException("No artifacts found under " + src);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(car))) {
            put(z, "artifacts.xml", root.toString().getBytes(StandardCharsets.UTF_8));
            Set<String> dirs = new LinkedHashSet<>();
            for (String k : entries.keySet()) dirs.add(k.substring(0, k.indexOf('/')) + "/");
            for (String d : dirs) { ZipEntry de = new ZipEntry(d); de.setTime(0L); z.putNextEntry(de); z.closeEntry(); }
            for (Map.Entry<String, byte[]> e : entries.entrySet()) put(z, e.getKey(), e.getValue());
        }
        System.out.println("Built " + car + " (" + count + " artifacts, " + Files.size(car) + " bytes)");
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws java.io.IOException {
        ZipEntry e = new ZipEntry(name); e.setTime(0L); z.putNextEntry(e); z.write(data); z.closeEntry();
    }
}
