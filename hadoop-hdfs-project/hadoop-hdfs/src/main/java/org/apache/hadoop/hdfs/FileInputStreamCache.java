package org.apache.hadoop.hdfs;

import java.io.FileInputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Time;

import com.google.common.collect.LinkedListMultimap;

class FileInputStreamCache {
  private final static Log LOG = LogFactory.getLog(FileInputStreamCache.class);
  private final static ScheduledThreadPoolExecutor executor
  = new ScheduledThreadPoolExecutor(1);
  private CacheCleaner cacheCleaner;
  final int maxCacheSize;
  final int expiryTimeMs;
  private boolean shouldRun = true;
  private LinkedListMultimap<Key, Value> map = LinkedListMultimap.create();

  /**
   * Expiry thread which makes sure that the file descriptors get closed
   * after a while.
   */
  class CacheCleaner implements Runnable {
    @Override
    public void run() {
      synchronized(FileInputStreamCache.this) {
        if (!shouldRun) return;
        long curTime = Time.monotonicNow();
        for (Iterator<Entry<Key, Value>> iter = map.entries().iterator();
              iter.hasNext();
              iter = map.entries().iterator()) {
          Entry<Key, Value> entry = iter.next();
          if (entry.getValue().getTime() + expiryTimeMs >= curTime) {
            break;
          }
          entry.getValue().close();
          map.remove(entry.getKey(), entry.getValue());
        }
      }
    }
  }

  static class Key {
    private final DatanodeID datanodeID;
    private final ExtendedBlock block;
    
    public Key(DatanodeID datanodeID, ExtendedBlock block) {
      this.datanodeID = datanodeID;
      this.block = block;
    }
    
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof FileInputStreamCache.Key)) {
        return false;
      }
      FileInputStreamCache.Key otherKey = (FileInputStreamCache.Key)other;
      return (datanodeID.equals(otherKey.datanodeID) && (block.equals(otherKey.block)));
    }
    
    @Override
    public int hashCode() {
      return datanodeID.hashCode() ^ block.hashCode();
    }
  }

  static class Value {
    private final FileInputStream fis[];
    private final long time;
    
    public Value (FileInputStream fis[]) {
      this.fis = fis;
      this.time = Time.monotonicNow();
    }

    public FileInputStream[] getFileInputStreams() {
      return fis;
    }

    public long getTime() {
      return time;
    }
    
    public void close() {
      IOUtils.cleanup(LOG, fis);
    }
  }
  
  public FileInputStreamCache(int maxCacheSize, int expiryTimeMs) {
    this.maxCacheSize = maxCacheSize;
    this.expiryTimeMs = expiryTimeMs;
  }
  
  public void put(DatanodeID datanodeID, ExtendedBlock block,
      FileInputStream fis[]) {
    boolean inserted = false;
    try {
      synchronized(this) {
        if (!shouldRun) return;
        if (map.size() + 1 > maxCacheSize) {
          Iterator<Entry<Key, Value>> iter = map.entries().iterator();
          if (!iter.hasNext()) return;
          Entry<Key, Value> entry = iter.next();
          entry.getValue().close();
          map.remove(entry.getKey(), entry.getValue());
        }
        if (cacheCleaner == null) {
          cacheCleaner = new CacheCleaner();
          executor.scheduleAtFixedRate(cacheCleaner, expiryTimeMs, expiryTimeMs, 
              TimeUnit.MILLISECONDS);
        }
        map.put(new Key(datanodeID, block), new Value(fis));
        inserted = true;
      }
    } finally {
      if (!inserted) {
        for (FileInputStream f : fis) {
          IOUtils.cleanup(LOG, f);
        }
      }
    }
  }
  
  public synchronized FileInputStream[] get(DatanodeID datanodeID,
      ExtendedBlock block) {
    Key key = new Key(datanodeID, block);
    List<Value> ret = map.get(key);
    if (ret.isEmpty()) return null;
    Value val = ret.get(0);
    map.remove(key, val);
    return val.getFileInputStreams();
  }
  
  /**
   * Close the cache and free all associated resources.
   */
  public synchronized void close() {
    if (!shouldRun) return;
    shouldRun = false;
    if (cacheCleaner != null) {
      executor.remove(cacheCleaner);
    }
    for (Iterator<Entry<Key, Value>> iter = map.entries().iterator();
          iter.hasNext();
          iter = map.entries().iterator()) {
      Entry<Key, Value> entry = iter.next();
      entry.getValue().close();
      map.remove(entry.getKey(), entry.getValue());
    }
  }
  
  public synchronized String toString() {
    StringBuilder bld = new StringBuilder();
    bld.append("FileInputStreamCache(");
    String prefix = "";
    for (Entry<Key, Value> entry : map.entries()) {
      bld.append(prefix);
      bld.append(entry.getKey());
      prefix = ", ";
    }
    bld.append(")");
    return bld.toString();
  }
  
  public int getExpiryTimeMs() {
    return expiryTimeMs;
  }
  
  public int getMaxCacheSize() {
    return maxCacheSize;
  }
}
