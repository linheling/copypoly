package com.caca.copypoly.geometry;

import java.util.*;

/** Immutable integer convex hull. All construction work is bounded by MAX_POINTS, never performed per tick. */
public final class Polyhedron {
    public static final int MAX_POINTS = 32;
    public static final int MAX_BOXES = 64;
    public record Point(int x, int y, int z) implements Comparable<Point> {
        public Point {
            if (x < 0 || x > 16 || y < 0 || y > 16 || z < 0 || z > 16) throw new IllegalArgumentException("range");
        }
        public int packed() { return x << 10 | y << 5 | z; }
        public static Point unpack(int n) {
            if (n < 0 || (n >>> 15) != 0) throw new IllegalArgumentException("range");
            return new Point(n >> 10, n >> 5 & 31, n & 31);
        }
        public int compareTo(Point p) { return Integer.compare(packed(), p.packed()); }
    }
    public record Plane(int x, int y, int z, int d) {
        public double distance(double a, double b, double c) { return x * a + y * b + z * c - d; }
    }
    public record Face(Plane plane, List<Point> vertices) {}
    /** Box coordinates use the same 0..16 units as points. */
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

    private static final Map<List<Point>, Polyhedron> CACHE = new LinkedHashMap<>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<List<Point>, Polyhedron> e) { return size() > 256; }
    };
    public static final List<Point> CUBE_POINTS = List.of(new Point(0,0,0), new Point(0,0,16), new Point(0,16,0),
        new Point(0,16,16), new Point(16,0,0), new Point(16,0,16), new Point(16,16,0), new Point(16,16,16));
    public static final Polyhedron CUBE = of(CUBE_POINTS);
    private final List<Point> points;
    private final List<Face> faces;
    private final Box bounds;
    private volatile List<Box> boxes;

    public List<Point> points() { return points; }
    public List<Face> faces() { return faces; }
    public int[] packed() { return points.stream().mapToInt(Point::packed).toArray(); }
    public static Polyhedron fromPacked(int[] data) {
        if (data.length > MAX_POINTS) throw new IllegalArgumentException("count");
        return of(Arrays.stream(data).mapToObj(Point::unpack).toList());
    }
    public static Polyhedron of(Collection<Point> input) {
        if (input.size() > MAX_POINTS) throw new IllegalArgumentException("count");
        List<Point> key = List.copyOf(new TreeSet<>(input));
        if (key.size() < 4) throw new IllegalArgumentException("count");
        synchronized (CACHE) {
            Polyhedron known = CACHE.get(key);
            if (known != null) return known;
        }
        Polyhedron built = new Polyhedron(key);
        synchronized (CACHE) { CACHE.put(key, built); }
        return built;
    }
    private Polyhedron(List<Point> pts) {
        points = pts;
        Set<Plane> planes = new LinkedHashSet<>();
        boolean volume = false;
        // Exact integer half-space tests avoid epsilon cracks and ambiguous coplanar triangles.
        for (int i=0;i<pts.size()-2;i++) for(int j=i+1;j<pts.size()-1;j++) for(int k=j+1;k<pts.size();k++) {
            Point a=pts.get(i), b=pts.get(j), c=pts.get(k);
            int ux=b.x-a.x, uy=b.y-a.y, uz=b.z-a.z, vx=c.x-a.x, vy=c.y-a.y, vz=c.z-a.z;
            int nx=uy*vz-uz*vy, ny=uz*vx-ux*vz, nz=ux*vy-uy*vx;
            int g=gcd(gcd(Math.abs(nx),Math.abs(ny)),Math.abs(nz));
            if(g==0) continue;
            nx/=g; ny/=g; nz/=g;
            int d=nx*a.x+ny*a.y+nz*a.z;
            boolean positive=false, negative=false;
            for(Point p:pts) {
                int side=nx*p.x+ny*p.y+nz*p.z-d;
                positive |= side>0; negative |= side<0;
                if(positive && negative) break;
            }
            volume |= positive || negative;
            if(positive && negative || !positive && !negative) continue;
            planes.add(positive ? new Plane(-nx,-ny,-nz,-d) : new Plane(nx,ny,nz,d));
        }
        if(!volume || planes.size()<4) throw new IllegalArgumentException("flat");
        List<Face> result=new ArrayList<>();
        for(Plane plane:planes) result.add(new Face(plane, polygon(pts,plane)));
        faces=List.copyOf(result);
        bounds=new Box(pts.stream().mapToInt(Point::x).min().orElseThrow(), pts.stream().mapToInt(Point::y).min().orElseThrow(),
            pts.stream().mapToInt(Point::z).min().orElseThrow(), pts.stream().mapToInt(Point::x).max().orElseThrow(),
            pts.stream().mapToInt(Point::y).max().orElseThrow(), pts.stream().mapToInt(Point::z).max().orElseThrow());
    }
    private static int gcd(int a,int b) { while(b!=0) {int t=a%b;a=b;b=t;} return a; }
    public static int dominant(Plane p) {
        int x=Math.abs(p.x),y=Math.abs(p.y),z=Math.abs(p.z);
        return y>=x && y>=z ? 1 : x>=z ? 0 : 2;
    }
    /**
     * True when the hull owns a face parallel to {@code axis}/{@code positive} that lies exactly on that
     * side of the block (plane offset 16 or 0 in sixteenths). Connected-texture code uses this to decide
     * whether the copied material is actually presented on that side, i.e. whether this block may claim
     * the material as its appearance and whether neighbours behind that side should count. Slanted faces
     * never qualify, even when they touch the border along an edge.
     */
    public boolean faceAtBoundary(int axis,boolean positive) {
        for(Face face:faces) {
            int nx=face.plane().x(),ny=face.plane().y(),nz=face.plane().z();
            int others=axis==0 ? Math.abs(ny)+Math.abs(nz) : axis==1 ? Math.abs(nx)+Math.abs(nz) : Math.abs(nx)+Math.abs(ny);
            if(others!=0) continue;
            int normal=axis==0 ? nx : axis==1 ? ny : nz;
            if((normal>0)!=positive) continue;
            if(face.plane().d()!=(positive ? 16 : 0)) continue;
            return true;
        }
        return false;
    }
    /**
     * Whether a connected-texture probe at this block offset should see the real world. Every axis the
     * offset moves along must be a side where the hull presents a boundary face, so a probe towards a side
     * the hull does not fill reads as air (no connection invented). Corners matter here: Create's
     * {@code buildContext} probes corner bits at {@code pos.relative(horizontal, +-1).relative(vertical, +-1)},
     * i.e. two axes at once, and Fusion/Continuity look up to two blocks out. Rejecting anything but
     * single-axis offsets leaves every corner bit permanently false - "edges connect, corners do not".
     */
    public boolean showsOffset(int dx,int dy,int dz) {
        if(dx==0 && dy==0 && dz==0) return true; // the block itself stays visible to the query
        if(Math.abs(dx)>2 || Math.abs(dy)>2 || Math.abs(dz)>2) return false;
        if(dx!=0 && !faceAtBoundary(0,dx>0)) return false;
        if(dy!=0 && !faceAtBoundary(1,dy>0)) return false;
        if(dz!=0 && !faceAtBoundary(2,dz>0)) return false;
        return true;
    }
    /**
     * Whether the hull presents a surface toward this side of the block, and actually reaches that side.
     * This is the appearance test for the copied material: a slanted face still counts as "the material is
     * visible from above" as long as the hull touches y = 16 somewhere, which is what lets edited shapes
     * (triangular prisms, ramps, clipped corners) join connected textures across a block border. Unlike
     * {@link #faceAtBoundary} the face does not have to be parallel to the block side, and unlike a bare
     * "some face looks that way" test a hull that stops short of the border does not claim anything.
     */
    public boolean presents(int axis,boolean positive) {
        boolean facing=false;
        int reach=positive ? -16 : 32;
        for(Face face:faces) {
            if(face.vertices()==null || face.vertices().isEmpty()) continue;
            Plane normal=face.plane();
            int component=axis==0 ? normal.x() : axis==1 ? normal.y() : normal.z();
            if(positive ? component>0 : component<0) facing=true;
            for(Point vertex:face.vertices()) {
                int coordinate=axis==0 ? vertex.x() : axis==1 ? vertex.y() : vertex.z();
                reach=positive ? Math.max(reach,coordinate) : Math.min(reach,coordinate);
            }
        }
        return facing && reach==(positive ? 16 : 0);
    }
    private static int u(Point p,int axis) { return axis==0 ? p.z : p.x; }
    private static int v(Point p,int axis) { return axis==1 ? p.z : p.y; }
    private static long cross(Point a,Point b,Point c,int axis) {
        return (long)(u(b,axis)-u(a,axis))*(v(c,axis)-v(a,axis))-(long)(v(b,axis)-v(a,axis))*(u(c,axis)-u(a,axis));
    }
    private static List<Point> polygon(List<Point> points,Plane plane) {
        int axis=dominant(plane);
        List<Point> p=points.stream().filter(t->plane.distance(t.x,t.y,t.z)==0)
            .sorted(Comparator.comparingInt((Point t)->u(t,axis)).thenComparingInt(t->v(t,axis))).toList();
        List<Point> h=new ArrayList<>();
        for(Point t:p) { while(h.size()>=2 && cross(h.get(h.size()-2),h.getLast(),t,axis)<=0) h.removeLast(); h.add(t); }
        int lower=h.size();
        for(int i=p.size()-2;i>=0;i--) { Point t=p.get(i); while(h.size()>lower && cross(h.get(h.size()-2),h.getLast(),t,axis)<=0) h.removeLast(); h.add(t); }
        h.removeLast();
        Point a=h.get(0),b=h.get(1),c=h.get(2);
        int nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
        int ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
        int nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
        if(nx*plane.x+ny*plane.y+nz*plane.z<0) Collections.reverse(h);
        return List.copyOf(h);
    }
    public boolean contains(double x,double y,double z) {
        return faces.stream().allMatch(f->f.plane.distance(x,y,z)<=1e-8);
    }
    public List<Box> collisionBoxes() {
        List<Box> b=boxes;
        if(b!=null) return b;
        for(int resolution:new int[]{8,4,2}) {
            b=voxelize(resolution);
            if(b.size()<=MAX_BOXES) break;
        }
        boxes=List.copyOf(b);
        return boxes;
    }
    private List<Box> voxelize(int n) {
        double step=16.0/n;
        boolean[][][] cells=new boolean[n][n][n];
        for(int x=0;x<n;x++) for(int y=0;y<n;y++) for(int z=0;z<n;z++) {
            double x0=Math.max(x*step,bounds.x0), y0=Math.max(y*step,bounds.y0), z0=Math.max(z*step,bounds.z0);
            double x1=Math.min((x+1)*step,bounds.x1), y1=Math.min((y+1)*step,bounds.y1), z1=Math.min((z+1)*step,bounds.z1);
            if(x0>=x1 || y0>=y1 || z0>=z1) continue;
            boolean hit=true;
            // Conservative plane rejection: never loses thin valid hulls (unlike center sampling).
            for(Face f:faces) {Plane p=f.plane; if(p.distance(p.x>=0?x0:x1,p.y>=0?y0:y1,p.z>=0?z0:z1)>=0) {hit=false;break;}}
            cells[x][y][z]=hit;
        }
        List<Box> result=new ArrayList<>();
        for(int x=0;x<n;x++) for(int y=0;y<n;y++) for(int z=0;z<n;z++) {
            if(!cells[x][y][z]) continue;
            int xe=x+1,ye=y+1,ze=z+1;
            while(xe<n && cells[xe][y][z]) xe++;
            outer: while(ye<n) {for(int a=x;a<xe;a++) if(!cells[a][ye][z]) break outer; ye++;}
            outer: while(ze<n) {for(int a=x;a<xe;a++) for(int b=y;b<ye;b++) if(!cells[a][b][ze]) break outer; ze++;}
            for(int a=x;a<xe;a++) for(int b=y;b<ye;b++) for(int c=z;c<ze;c++) cells[a][b][c]=false;
            result.add(new Box(Math.max(x*step,bounds.x0),Math.max(y*step,bounds.y0),Math.max(z*step,bounds.z0),
                Math.min(xe*step,bounds.x1),Math.min(ye*step,bounds.y1),Math.min(ze*step,bounds.z1)));
        }
        return result;
    }
    public static List<Point> parse(String text) {
        List<Point> result=new ArrayList<>();
        for(String line:text.strip().split("\\R|;")) {
            if(line.isBlank()) continue;
            String[] xyz=line.strip().replace("(","").replace(")","").split("[\\s,，]+");
            if(xyz.length!=3) throw new IllegalArgumentException("format");
            try {result.add(new Point(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));}
            catch(NumberFormatException e) {throw new IllegalArgumentException("format");}
            if(result.size()>MAX_POINTS) throw new IllegalArgumentException("count");
        }
        return result;
    }
}
