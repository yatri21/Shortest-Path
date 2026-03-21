import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class Dijkstra {

    private final Map<String, List<int[]>> adj = new HashMap<>();
    private final List<String> nodes = new ArrayList<>();
    private final List<String[]> edges = new ArrayList<>();

    public void addNode(String name) {
        if (!adj.containsKey(name)) {
            adj.put(name, new ArrayList<>());
            nodes.add(name);
        }
    }

    public boolean addEdge(String from, String to, int weight) {
        if (!adj.containsKey(from) || !adj.containsKey(to)) return false;
        for (String[] e : edges)
            if ((e[0].equals(from) && e[1].equals(to)) || (e[0].equals(to) && e[1].equals(from)))
                return false;
        int fi = nodes.indexOf(from), ti = nodes.indexOf(to);
        adj.get(from).add(new int[]{ti, weight});
        adj.get(to).add(new int[]{fi, weight});
        edges.add(new String[]{from, to, String.valueOf(weight)});
        return true;
    }

    public boolean removeNode(String name) {
        if (!adj.containsKey(name)) return false;
        nodes.remove(name);
        adj.remove(name);
        edges.removeIf(e -> e[0].equals(name) || e[1].equals(name));
        // Remove from all adjacency lists
        for (String n : nodes) {
            adj.get(n).removeIf(nb -> nb[0] == nodes.indexOf(name));
        }
        // Rebuild adj indices after removal
        rebuildAdj();
        return true;
    }

    public boolean removeEdge(String from, String to) {
        boolean removed = edges.removeIf(e ->
            (e[0].equals(from) && e[1].equals(to)) ||
            (e[0].equals(to)   && e[1].equals(from)));
        if (removed) rebuildAdj();
        return removed;
    }

    private void rebuildAdj() {
        for (String n : nodes) adj.put(n, new ArrayList<>());
        for (String[] e : edges) {
            int fi = nodes.indexOf(e[0]), ti = nodes.indexOf(e[1]);
            int w = Integer.parseInt(e[2]);
            adj.get(e[0]).add(new int[]{ti, w});
            adj.get(e[1]).add(new int[]{fi, w});
        }
    }

    private int[][] dijkstra(int srcIdx) {
        int n = nodes.size();
        int[] dist = new int[n], prev = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        dist[srcIdx] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
        pq.offer(new int[]{0, srcIdx});
        boolean[] visited = new boolean[n];
        while (!pq.isEmpty()) {
            int[] curr = pq.poll();
            int d = curr[0], u = curr[1];
            if (visited[u]) continue;
            visited[u] = true;
            for (int[] nb : adj.get(nodes.get(u))) {
                int v = nb[0], w = nb[1];
                if (!visited[v] && dist[u] != Integer.MAX_VALUE && dist[u] + w < dist[v]) {
                    dist[v] = dist[u] + w;
                    prev[v] = u;
                    pq.offer(new int[]{dist[v], v});
                }
            }
        }
        return new int[][]{dist, prev};
    }

    private String reconstructPath(int[] prev, int srcIdx, int destIdx) {
        List<String> path = new LinkedList<>();
        for (int at = destIdx; at != -1; at = prev[at]) path.add(0, nodes.get(at));
        if (path.isEmpty() || !path.get(0).equals(nodes.get(srcIdx))) return "";
        return String.join(" -> ", path);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String graphJson() {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(nodes.get(i)).append("\"");
        }
        sb.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) sb.append(",");
            String[] e = edges.get(i);
            sb.append("{\"from\":\"").append(e[0])
              .append("\",\"to\":\"").append(e[1])
              .append("\",\"weight\":").append(e[2]).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String dijkstraJson(String src, String tgt) {
        int si = nodes.indexOf(src);
        if (si == -1) return "{\"error\":\"Source node not found\"}";
        int[][] result = dijkstra(si);
        int[] dist = result[0], prev = result[1];

        StringBuilder sb = new StringBuilder("{\"source\":\"").append(src).append("\"");

        if (tgt != null && !tgt.isEmpty()) {
            int di = nodes.indexOf(tgt);
            if (di == -1) return "{\"error\":\"Target node not found\"}";
            String path = reconstructPath(prev, si, di);
            int d = dist[di];
            sb.append(",\"target\":\"").append(tgt).append("\"");
            sb.append(",\"distance\":").append(d == Integer.MAX_VALUE ? "-1" : d);
            sb.append(",\"path\":\"").append(d == Integer.MAX_VALUE ? "" : path).append("\"");
        } else {
            sb.append(",\"all\":[");
            for (int i = 0; i < nodes.size(); i++) {
                if (i > 0) sb.append(",");
                int d = dist[i];
                String path = d == Integer.MAX_VALUE ? "" : reconstructPath(prev, si, i);
                sb.append("{\"node\":\"").append(nodes.get(i)).append("\"")
                  .append(",\"distance\":").append(d == Integer.MAX_VALUE ? -1 : d)
                  .append(",\"path\":\"").append(path).append("\"}");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    // ── HTTP Server ───────────────────────────────────────────────────────────

    public void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // CORS + routing
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1); return;
            }

            String path   = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body   = new String(exchange.getRequestBody().readAllBytes());
            String query  = exchange.getRequestURI().getQuery();
            Map<String,String> params = parseQuery(query);

            String response;
            int status = 200;

            try {
                // GET /graph  — return current graph
                if (method.equals("GET") && path.equals("/graph")) {
                    response = graphJson();

                // POST /node  body: {"name":"A"}
                } else if (method.equals("POST") && path.equals("/node")) {
                    String name = jsonField(body, "name").toUpperCase();
                    if (name.isEmpty()) { status=400; response="{\"error\":\"Missing name\"}"; }
                    else if (nodes.contains(name)) { status=409; response="{\"error\":\"Node exists\"}"; }
                    else { addNode(name); response="{\"ok\":true}"; System.out.println("[+] Node: " + name); }

                // DELETE /node?name=A
                } else if (method.equals("DELETE") && path.equals("/node")) {
                    String name = params.getOrDefault("name","").toUpperCase();
                    boolean ok = removeNode(name);
                    response = ok ? "{\"ok\":true}" : "{\"error\":\"Node not found\"}";
                    if (ok) System.out.println("[-] Node: " + name);

                // POST /edge  body: {"from":"A","to":"B","weight":3}
                } else if (method.equals("POST") && path.equals("/edge")) {
                    String from = jsonField(body,"from").toUpperCase();
                    String to   = jsonField(body,"to").toUpperCase();
                    int    wt   = jsonInt(body,"weight",1);
                    boolean ok  = addEdge(from, to, wt);
                    response = ok ? "{\"ok\":true}" : "{\"error\":\"Edge invalid or duplicate\"}";
                    if (ok) System.out.println("[+] Edge: " + from + " <-> " + to + " w=" + wt);

                // DELETE /edge?from=A&to=B
                } else if (method.equals("DELETE") && path.equals("/edge")) {
                    String from = params.getOrDefault("from","").toUpperCase();
                    String to   = params.getOrDefault("to","").toUpperCase();
                    boolean ok  = removeEdge(from, to);
                    response = ok ? "{\"ok\":true}" : "{\"error\":\"Edge not found\"}";
                    if (ok) System.out.println("[-] Edge: " + from + " <-> " + to);

                // GET /dijkstra?src=A&tgt=E  or  ?src=A  (all distances)
                } else if (method.equals("GET") && path.equals("/dijkstra")) {
                    String src = params.getOrDefault("src","").toUpperCase();
                    String tgt = params.getOrDefault("tgt","");
                    if (src.isEmpty()) { status=400; response="{\"error\":\"src required\"}"; }
                    else { response = dijkstraJson(src, tgt.toUpperCase()); System.out.println("[~] Dijkstra src=" + src + (tgt.isEmpty()?"":" tgt="+tgt.toUpperCase())); }

                // POST /reset
                } else if (method.equals("POST") && path.equals("/reset")) {
                    nodes.clear(); edges.clear(); adj.clear();
                    response="{\"ok\":true}"; System.out.println("[*] Graph reset");

                // POST /example
                } else if (method.equals("POST") && path.equals("/example")) {
                    nodes.clear(); edges.clear(); adj.clear();
                    addNode("A"); addNode("B"); addNode("C"); addNode("D"); addNode("E");
                    addEdge("A","B",1); addEdge("A","C",4); addEdge("B","C",2);
                    addEdge("B","D",5); addEdge("C","D",1); addEdge("D","E",3); addEdge("B","E",7);
                    response=graphJson(); System.out.println("[*] Example graph loaded");

                } else { status=404; response="{\"error\":\"Not found\"}"; }

            } catch (Exception ex) {
                status=500; response="{\"error\":\"" + ex.getMessage() + "\"}";
            }

            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });

        server.setExecutor(null);
        server.start();
        System.out.println("========================================");
        System.out.println("  Dijkstra HTTP Server running on :"+port);
        System.out.println("  Open dijkstra.html in your browser");
        System.out.println("  Press Ctrl+C to stop");
        System.out.println("========================================");
    }

    // ── Utility parsers ───────────────────────────────────────────────────────

    private static Map<String,String> parseQuery(String query) {
        Map<String,String> map = new HashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=",2);
            if (kv.length==2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static String jsonField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx + pattern.length());
        if (colon < 0) return "";
        int start = json.indexOf("\"", colon + 1);
        int end   = json.indexOf("\"", start + 1);
        if (start < 0 || end < 0) return "";
        return json.substring(start+1, end);
    }

    private static int jsonInt(String json, String key, int def) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return def;
        int colon = json.indexOf(":", idx + pattern.length());
        if (colon < 0) return def;
        StringBuilder num = new StringBuilder();
        for (int i = colon+1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        try { return Integer.parseInt(num.toString()); } catch (Exception e) { return def; }
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        new Dijkstra().startServer(port);
    }
}