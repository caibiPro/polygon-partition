package com.geo.split;

import org.locationtech.jts.geom.*;
import java.util.*;

/**
 * 三种方案的完整对比测试
 */
public class VillageSplitThreeWayComparisonRunner {

    private static final GeometryFactory gf = new GeometryFactory();

    static TestData makeGrid(int rows, int cols, double w, double h, double gap, long seed) {
        Random rng = new Random(seed);
        double cw = w / cols, ch = h / rows;
        double[][] gx = new double[rows+1][cols+1];
        double[][] gy = new double[rows+1][cols+1];
        for (int i = 0; i <= rows; i++)
            for (int j = 0; j <= cols; j++) {
                gx[i][j] = j * cw; gy[i][j] = i * ch;
                if (i > 0 && i < rows && j > 0 && j < cols) {
                    gx[i][j] += (rng.nextDouble()-0.5)*cw*0.15;
                    gy[i][j] += (rng.nextDouble()-0.5)*ch*0.15;
                }
            }
        Polygon village = gf.createPolygon(new Coordinate[]{
            new Coordinate(0,0), new Coordinate(w,0),
            new Coordinate(w,h), new Coordinate(0,h), new Coordinate(0,0)});
        List<Geometry> parcels = new ArrayList<>();
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                try {
                    Polygon p = gf.createPolygon(new Coordinate[]{
                        new Coordinate(gx[i][j]+gap, gy[i][j]+gap),
                        new Coordinate(gx[i][j+1]-gap, gy[i][j+1]+gap),
                        new Coordinate(gx[i+1][j+1]-gap, gy[i+1][j+1]-gap),
                        new Coordinate(gx[i+1][j]+gap, gy[i+1][j]-gap),
                        new Coordinate(gx[i][j]+gap, gy[i][j]+gap)});
                    if (p.isValid() && p.getArea() > 0) parcels.add(p);
                } catch (Exception e) {}
            }
        return new TestData(village, parcels);
    }

    static class TestData { 
        final Geometry village; final List<Geometry> parcels;
        TestData(Geometry v, List<Geometry> p) { village=v; parcels=p; }
    }

    static void compare(String label, TestData data, int threshold) {
        System.out.println("\n" + "=".repeat(75));
        System.out.printf("  %s  |  N=%d  threshold=%d%n", label, data.parcels.size(), threshold);
        System.out.println("=".repeat(75));

        // A: Coord bisection
        TopologySafeBisectionSplitter cs = new TopologySafeBisectionSplitter();
        long t0 = System.currentTimeMillis();
        var creg = cs.split(data.village, data.parcels, threshold);
        printResult("Coord bisection", creg.stream().mapToInt(r->r.parcels.size()).toArray(),
            System.currentTimeMillis()-t0, threshold);

        // B: Graph partition (METIS)
        MultilevelGraphPartitionSplitter gs = new MultilevelGraphPartitionSplitter();
        t0 = System.currentTimeMillis();
        var greg = gs.split(data.village, data.parcels, threshold);
        printResult("METIS partition", greg.stream().mapToInt(r->r.parcels.size()).toArray(),
            System.currentTimeMillis()-t0, threshold);

        // C: MST tree cut
        MaxSpanningTreePartitioner ms = new MaxSpanningTreePartitioner();
        t0 = System.currentTimeMillis();
        var mreg = ms.split(data.village, data.parcels, threshold);
        long mstMs = System.currentTimeMillis()-t0;
        printResult("MST tree cut  ", mreg.stream().mapToInt(r->r.parcels.size()).toArray(),
            mstMs, threshold);
        System.out.println("    " + ms.analyzeMST(data.parcels));
        var issues = ms.validate(data.parcels, mreg, threshold);
        System.out.println("    Validation: " + issues.get(0));
    }

    static void printResult(String name, int[] sizes, long ms, int threshold) {
        int max=Arrays.stream(sizes).max().orElse(0);
        int min=Arrays.stream(sizes).min().orElse(0);
        double avg=Arrays.stream(sizes).average().orElse(0);
        double bf = avg>0 ? max/avg : 0;
        System.out.printf("  %-16s | %3d regions | %5dms | [%d..%d] avg=%.1f | BF=%.2f %s%n",
            name, sizes.length, ms, min, max, avg, bf, max>threshold?"OVER":"OK");
    }

    public static void main(String[] args) {
        System.out.println("Three-way comparison: Coord vs METIS vs MST\n");
        compare("Small 8x8",   makeGrid(8,8,  100,100,0.3,42), 10);
        compare("Medium 15x15",makeGrid(15,15,500,500,0.2,42), 30);
        compare("Elongated 4x25",makeGrid(4,25,500,80,0.2,42),15);
        compare("Large 25x25", makeGrid(25,25,1000,1000,0.2,42),80);

        System.out.println("\n\nPerformance scaling (threshold=N/8):");
        System.out.printf("  %-8s %-8s %-12s %-12s %-12s%n","N","Thresh","Coord","METIS","MST");
        for (int d : new int[]{8,12,16,20,25,30}) {
            var data = makeGrid(d,d,1000,1000,0.2,42);
            int n=data.parcels.size(), th=Math.max(8,n/8);
            long t,m1,m2,m3;
            t=System.currentTimeMillis();
            new TopologySafeBisectionSplitter().split(data.village,data.parcels,th);
            m1=System.currentTimeMillis()-t;
            t=System.currentTimeMillis();
            new MultilevelGraphPartitionSplitter().split(data.village,data.parcels,th);
            m2=System.currentTimeMillis()-t;
            t=System.currentTimeMillis();
            new MaxSpanningTreePartitioner().split(data.village,data.parcels,th);
            m3=System.currentTimeMillis()-t;
            System.out.printf("  %-8d %-8d %8dms   %8dms   %8dms%n",n,th,m1,m2,m3);
        }
    }
}
