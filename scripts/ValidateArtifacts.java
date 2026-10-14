import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class ValidateArtifacts {
    public static void main(String[] a) throws Exception {
        Path root = Paths.get(a[0]);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) { files = s.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList()); }
        Map<String, Path> names = new HashMap<>();
        List<String> errors = new ArrayList<>();
        Map<Path, Document> docs = new LinkedHashMap<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        for (Path f : files) {
            try {
                Document d = dbf.newDocumentBuilder().parse(f.toFile());
                String n = d.getDocumentElement().getAttribute("name");
                if (n.isEmpty()) { errors.add(f + ": root element has no name attribute"); continue; }
                if (names.put(n, f) != null) errors.add("duplicate artifact name: " + n);
                docs.put(f, d);
            } catch (Exception e) { errors.add(f + ": not well-formed XML (" + e.getMessage() + ")"); }
        }
        Set<String> builtin = Set.of("main", "fault");
        int refs = 0;
        for (Map.Entry<Path, Document> e : docs.entrySet()) {
            for (String tag : new String[]{"sequence", "endpoint"}) {
                NodeList nl = e.getValue().getElementsByTagNameNS("*", tag);
                for (int i = 0; i < nl.getLength(); i++) {
                    String key = ((Element) nl.item(i)).getAttribute("key");
                    if (key.isEmpty()) continue;
                    refs++;
                    if (!names.containsKey(key) && !builtin.contains(key))
                        errors.add(e.getKey().getFileName() + ": references " + tag + " '" + key + "' which does not exist");
                }
            }
        }
        System.out.println("Checked " + docs.size() + " artifacts, " + refs + " references");
        if (!errors.isEmpty()) { errors.forEach(x -> System.out.println("FAIL " + x)); System.exit(1); }
        System.out.println("OK");
    }
}
