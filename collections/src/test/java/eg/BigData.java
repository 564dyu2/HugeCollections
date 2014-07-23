package eg;

import net.openhft.affinity.AffinitySupport;
import net.openhft.collections.SharedHashMapBuilder;
import net.openhft.lang.io.Bytes;

import java.io.*;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/*
 tune the kernel to maximise the amount of cached write data.

 vm.dirty_background_ratio = 30
 vm.dirty_expire_centisecs = 30000
 vm.dirty_ratio = 60
 vm.dirty_writeback_centisecs = 3000
 */
public class BigData {
    static Map<Long, BigDataStuff> TheSharedMap;
    final static long MAXSIZE = 1000 * 1000 * 1000L;
    //run 1st test with no map, and Highwatermark set to 0
    //then switch to Highwatermark set to MAXSIZE for subsequent test repeats
    static AtomicInteger Highwatermark = new AtomicInteger((int) MAXSIZE);
//    static AtomicInteger Highwatermark = new AtomicInteger(0);

    static final SharedHashMapBuilder builder = new SharedHashMapBuilder();

    static {
        builder.largeSegments(true);
        builder.actualSegments(8 * 1024);
        builder.entries(MAXSIZE);
        String dir = System.getProperty("dir", "/ocz/tmp");
        if (!new File("/ocz/tmp").exists()) dir = ".";
        String shmPath = dir + "/testmap-" + Long.toString(System.nanoTime(), 36);
        new File(shmPath).deleteOnExit();
        System.out.println("SharedHashMap entries() = " + builder.entries());
        try {
            TheSharedMap = builder.create(new File(shmPath), Long.class, BigDataStuff.class);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        initialbuild();
        System.out.println("Start highwatermark " + Highwatermark.get());
        for (int i = 0; i < 10; i++) {
            Thread t1 = new Thread("test 1") {
                public void run() {
                    _test();
                }
            };
            Thread t2 = new Thread("test 2") {
                public void run() {
                    _test();
                }
            };
            Thread t3 = new Thread("test 3") {
                public void run() {
                    _test();
                }
            };
            t1.start();
            t2.start();
            t3.start();
            _test();
            t1.join();
            t2.join();
            t3.join();
        }
        System.out.println("End highwatermark " + Highwatermark.get());
        long time = System.currentTimeMillis() - start;
        System.out.printf("End to end took %.1f%n", time / 1e3);
    }

    public static void initialbuild() throws IOException, InterruptedException {
        System.out.println("building an empty map");
        long start = System.currentTimeMillis();
        Thread t1 = new Thread("test 1") {
            public void run() {
                populate(1);
            }
        };
        t1.start();
        Thread t2 = new Thread("test 2") {
            public void run() {
                populate(2);
            }
        };
        t2.start();
        Thread t3 = new Thread("test 3") {
            public void run() {
                populate(3);
            }
        };
        t3.start();
        populate(0);
        t1.join();
        t2.join();
        t3.join();
        long now = System.currentTimeMillis();
        System.out.println(builder);
        System.out.println("Time taken to insert all entries " + ((now - start) / 1000.0) + " seconds");
    }

    public static void populate(int n) {
        AffinitySupport.setThreadId();

        long start = System.currentTimeMillis();
        BigDataStuff value = new BigDataStuff(0);
        for (long i = n; i < MAXSIZE; i += 4) {
            if (n == 0 && i % (10 * 1000 * 1000) == 0) {
                System.out.println("Now inserted to " + i + " seconds since start = " + ((System.currentTimeMillis() - start) / 1000L));
            }
            value.x = i;
            value.y.setLength(0);
            value.y.append(i);
            TheSharedMap.put(i, value);
        }
    }

    public static void _test() {
        // improves logging of these threads.
        AffinitySupport.setThreadId();
        try {
            test();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public static void test() throws IOException {
        //do a sequence 1m of each of insert/read/update
        //inserts
        long LOOPCOUNT = 100 * 1000L;
        Random rand = new Random();
        long start = System.currentTimeMillis();
        BigDataStuff value = new BigDataStuff(0);
        for (long i = 0; i < LOOPCOUNT; i++) {
            long current = rand.nextInt(Highwatermark.get());
            value.x = current;
            value.y.setLength(0);
            value.y.append(current);
            TheSharedMap.put(i, value);
        }
        long now = System.currentTimeMillis();
        System.out.println("Time taken to insert 100k entries " + ((now - start) / 1000.0) + " seconds");

        int count = 0;
        start = System.currentTimeMillis();
        for (long i = 0; i < LOOPCOUNT; i++) {
            long keyval = rand.nextInt(Highwatermark.get());
            count++;
            BigDataStuff stuff = TheSharedMap.get(keyval);
            if (stuff == null) {
                System.out.println("hit an empty at key " + keyval);
            }
        }
        now = System.currentTimeMillis();
        System.out.println("Time taken to read " + count + " entries of 100k attempts " + ((now - start) / 1000.0) + " seconds");

        start = System.currentTimeMillis();
        count = 0;
        for (long i = 0; i < LOOPCOUNT; i++) {
            long keyval = rand.nextInt(Highwatermark.get());
            BigDataStuff stuff = TheSharedMap.get(keyval);
            if (stuff == null) {
                System.out.println("hit an empty at key " + keyval);
            } else {
                count++;
                stuff.x++;
                stuff.y.append('1');
                TheSharedMap.put(keyval, stuff);
            }
        }
        now = System.currentTimeMillis();
        System.out.println("Time taken to read+update " + count + " entries of 100k attempts " + ((now - start) / 1000.0) + " seconds");
    }
}

class BigDataStuff implements Externalizable {
    long x;
    StringBuilder y = new StringBuilder();

    public BigDataStuff(long x) {
        this.x = x;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        Bytes b = (Bytes) out;
        b.writeStopBit(x);
        b.writeUTFΔ(y);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        Bytes b = (Bytes) in;
        this.x = b.readStopBit();
        if (this.y == null)
            this.y = new StringBuilder();
        b.readUTFΔ(this.y);
    }
}
