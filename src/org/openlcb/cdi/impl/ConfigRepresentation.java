package org.openlcb.cdi.impl;

import org.openlcb.EventID;
import org.openlcb.NodeID;
import org.openlcb.OlcbInterface;
import org.openlcb.DefaultPropertyListenerSupport;
import org.openlcb.cdi.CdiRep;
import org.openlcb.cdi.jdom.CdiMemConfigReader;
import org.openlcb.cdi.jdom.JdomCdiReader;
import org.openlcb.cdi.jdom.XmlHelper;
import org.openlcb.implementations.MemoryConfigurationService;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Maintains a parsed cache of the CDI config of a remote node. Responsible for fetching the CDI,
 * parsing the XML, identifying all variables with their correct offsets and creating useful
 * internal representations of these variables. Performs reads and writes to the configuration
 * space.
 *
 * Created by bracz on 3/29/16.
 */
public class ConfigRepresentation extends DefaultPropertyListenerSupport {
    // Fires when the loading state changes.
    public static final String UPDATE_STATE = "UPDATE_STATE";
    // Fired when the CDI is loaded and the representation is ready.
    public static final String UPDATE_REP = "UPDATE_REP";
    // Fired when all the caches have been pre-filled.
    public static final String UPDATE_CACHE_COMPLETE = "UPDATE_CACHE_COMPLETE";
    // Fired on the individual internal entries when they are changed.
    public static final String UPDATE_ENTRY_DATA = "UPDATE_ENTRY_DATA";
    private static final String TAG = "ConfigRepresentation";
    private static final Logger logger = Logger.getLogger(TAG);
    private final OlcbInterface connection;
    private final NodeID remoteNodeID;
    private CdiRep cdiRep;
    private String state = "Uninitialized";
    private CdiContainer root = null;
    private final Map<Integer, MemorySpaceCache> spaces = new HashMap<>();

    /**
     * Connects to a node, populates the cache by fetching and parsing the CDI.
     * @param connection OpenLCB network.
     * @param remoteNodeID the node to fetch CDI from.
     */
    public ConfigRepresentation(OlcbInterface connection, NodeID remoteNodeID) {
        this.connection = connection;
        this.remoteNodeID = remoteNodeID;
        triggerFetchCdi();
    }

    /**
     * Retrieves the CDI from the remote node, and if successful, calls @link parseRep.
     */
    private void triggerFetchCdi() {
        new CdiMemConfigReader(remoteNodeID, connection,
                MemoryConfigurationService.SPACE_CDI).startLoadReader(new CdiMemConfigReader
                .ReaderAccess() {

            @Override
            public void provideReader(Reader r) {
                try {
                    cdiRep = new JdomCdiReader().getRep(XmlHelper.parseXmlFromReader(r));
                } catch (Exception e) {
                    String error = "Failed to parse CDI output: " + e.toString();
                    logger.warning(error);
                    setState(error);
                    return;
                }
                parseRep();
            }
        });
    }

    private void parseRep() {
        root = new Root(cdiRep);
        setState("Representation complete.");
        prefillCaches();
        firePropertyChange(UPDATE_REP, null, root);
    }

    int pendingCacheFills = 0;
    PropertyChangeListener prefillListener = new PropertyChangeListener() {
        @Override
        public void propertyChange(PropertyChangeEvent propertyChangeEvent) {
            if (propertyChangeEvent.getPropertyName().equals(MemorySpaceCache
                    .UPDATE_LOADING_COMPLETE)) {
                synchronized (this) {
                    if (--pendingCacheFills == 0) {
                        firePropertyChange(UPDATE_CACHE_COMPLETE, null, null);
                        for (MemorySpaceCache sp : spaces.values()) {
                            sp.removePropertyChangeListener(prefillListener);
                        }
                    }
                }
            }
        }
    };

    private void prefillCaches() {
        visit(new Visitor() {
                  @Override
                  public void visitLeaf(final CdiEntry e) {
                      MemorySpaceCache cache = getCacheForSpace(e.space);
                      cache.addRangeToCache(e.origin, e.origin + e.size);
                      cache.addRangeListener(e.origin, e.origin + e.size, new
                              PropertyChangeListener() {
                          @Override
                          public void propertyChange(PropertyChangeEvent event) {
                              e.firePropertyChange(UPDATE_ENTRY_DATA, null, null);
                          }
                      });
                  }
              }
        );
        pendingCacheFills = spaces.size();
        for (MemorySpaceCache sp : spaces.values()) {
            sp.addPropertyChangeListener(prefillListener);
            // This will send off the first read, then continue asynchronously.
            sp.fillCache();
        }
    }

    /**
     * @return the internal representation of the root entry. The root entry contains all
     * segments as children.
     */
    public CdiContainer getRoot() {
        return root;
    }

    private synchronized MemorySpaceCache getCacheForSpace(int space) {
        if (spaces.containsKey(space)) {
            return spaces.get(space);
        } else {
            MemorySpaceCache s = new MemorySpaceCache(connection, remoteNodeID, space);
            spaces.put(space, s);
            return s;
        }
    }

    /**
     * Performs a visitation of the entire tree (starting at the root node).
     * @param v is an implementation of a tree Visitor.
     */
    public void visit(Visitor v) {
        v.visitContainer(getRoot());
    }

    /**
     * Processes the CdiRep entries of a children of a group and builds the internal representation
     * for each entry.
     *
     * @param baseName name of the prefix of all these group entries
     * @param items    the list of CDI entries to render
     * @param output   the list of output variables to append to
     * @param origin   offset in the segment of the beginning of the group payload
     * @return the number of bytes (one repeat of) this group covers in the address space
     */
    private int processGroup(String baseName, int segment, List<CdiRep.Item> items,
                             List<CdiEntry> output, int origin) {
        if (items == null) return 0;
        int base = origin;
        for (int i = 0; i < items.size(); i++) {
            CdiRep.Item it = (CdiRep.Item) items.get(i);

            origin = origin + it.getOffset();
            CdiEntry entry = null;
            String name = baseName + "." + it.getName();
            if (it instanceof CdiRep.Group) {
                entry = new GroupEntry(name, (CdiRep.Group) it, segment, origin);
            } else if (it instanceof CdiRep.IntegerRep) {
                entry = new IntegerEntry(name, (CdiRep.IntegerRep) it, segment, origin);
            } else if (it instanceof CdiRep.EventID) {
                entry = new EventEntry(name, (CdiRep.EventID) it, segment, origin);
            } else if (it instanceof CdiRep.StringRep) {
                entry = new StringEntry(name, (CdiRep.StringRep) it, segment, origin);
            } else {
                System.err.println("could not process CDI entry type of " + it);
            }
            if (entry != null) {
                origin = entry.origin + entry.size;
                output.add(entry);
            }
        }
        return origin - base;
    }

    private void setState(String state) {
        this.state = state;
        firePropertyChange(UPDATE_STATE, null, this.state);
    }

    public String getStatus() {
        return state;
    }

    /**
     * Interface for all internal representation of nodes that have children.
     */
    public interface CdiContainer {
        List<CdiEntry> getEntries();
    }

    /**
     * Interface for traversing the tree of settings. The default implementation will call
     * visitLeaf for each variable, and recurse automatically for each node with children.
     */
    public static class Visitor {
        public void visitEntry(CdiEntry e) {
            if (e instanceof StringEntry) {
                visitString((StringEntry) e);
            } else if (e instanceof IntegerEntry) {
                visitInt((IntegerEntry) e);
            } else if (e instanceof EventEntry) {
                visitEvent((EventEntry) e);
            } else if (e instanceof GroupRep) {
                visitGroupRep((GroupRep) e);
            } else if (e instanceof GroupEntry) {
                visitGroup((GroupEntry) e);
            } else if (e instanceof SegmentEntry) {
                visitSegment((SegmentEntry) e);
            } else if (e instanceof CdiContainer) {
                visitContainer((CdiContainer) e);
            } else {
                logger.warning("Don't know how to visit entry: " + e.getClass().getName());
            }
        }

        public void visitLeaf(CdiEntry e) {
        }

        public void visitString(StringEntry e) {
            visitLeaf(e);
        }

        public void visitInt(IntegerEntry e) {
            visitLeaf(e);
        }

        public void visitEvent(EventEntry e) {
            visitLeaf(e);
        }

        public void visitGroupRep(GroupRep e) {
            visitContainer(e);
        }

        public void visitGroup(GroupEntry e) {
            visitContainer(e);
        }

        public void visitSegment(SegmentEntry e) {
            visitContainer(e);
        }

        public void visitContainer(CdiContainer c) {
            for (CdiEntry e : c.getEntries()) {
                visitEntry(e);
            }
        }
    }

    /**
     * Base class for all internal representations of the nodes (both variables as well as groups
     * and segments).
     */
    public abstract class CdiEntry {
        /// Memory space number.
        public int space;
        /// Address of the first byte of this item in the memory space.
        public int origin;
        /// The number of bytes that this component takes in the configuration space.
        public int size;
        /// Internal key for this variable or group
        public String key;

        java.beans.PropertyChangeSupport pcs = new java.beans.PropertyChangeSupport(this);
        public synchronized void addPropertyChangeListener(java.beans.PropertyChangeListener l) {
            pcs.addPropertyChangeListener(l);
        }

        public synchronized void removePropertyChangeListener(java.beans.PropertyChangeListener l) {
            pcs.removePropertyChangeListener(l);
        }

        protected void firePropertyChange(String p, Object old, Object n) {
            pcs.firePropertyChange(p, old, n);
        }

        public abstract CdiRep.Item getCdiItem();
    }

    public class Root implements CdiContainer {
        public final List<CdiEntry> items;
        public final CdiRep rep;

        /**
         * Parses the root of the CdiRep into an internal representation that is a container.
         * @param rep
         */
        public Root(CdiRep rep) {
            items = new ArrayList<>();
            this.rep = rep;
            for (CdiRep.Segment e : rep.getSegments()) {
                items.add(new SegmentEntry(e));
            }
        }

        @Override
        public List<CdiEntry> getEntries() {
            return items;
        }
    }

    /**
     * Represents a Segment that looks like a group as well as an Entry to allow common handling
     * of groups and segments.
     */
    public class SegmentEntry extends CdiEntry implements CdiContainer, CdiRep.Item {
        public final CdiRep.Segment segment;
        public final List<CdiEntry> items;

        public SegmentEntry(CdiRep.Segment segment) {
            this.segment = segment;
            this.items = new ArrayList<>();
            this.key = getName();
            this.origin = segment.getOrigin();
            this.space = segment.getSpace();
            this.size = processGroup(key, this.space, segment.getItems(), this.items, this.origin);
        }

        @Override
        public List<CdiEntry> getEntries() {
            return items;
        }

        @Override
        public CdiRep.Item getCdiItem() {
            return this;
        }

        @Override
        public String getName() {
            return segment.getName();
        }

        @Override
        public String getDescription() {
            return segment.getDescription();
        }

        @Override
        public CdiRep.Map getMap() {
            return segment.getMap();
        }

        @Override
        public int getOffset() {
            return segment.getOrigin();
        }
    }

    /**
     * Base class for both repeated and non-repeated groups.
     */
    public class GroupBase extends CdiEntry implements CdiContainer {
        public final CdiRep.Group group;
        public final List<CdiEntry> items;

        public GroupBase(String name, CdiRep.Group group, int segment, int origin) {
            this.key = name;
            this.space = segment;
            this.origin = origin;
            this.group = group;
            this.items = new ArrayList<>();
        }

        @Override
        public List<CdiEntry> getEntries() {
            return items;
        }

        @Override
        public CdiRep.Item getCdiItem() {
            return group;
        }
    }

    /**
     * Represents one repeat of a repeated group. Contains a unique entry of all children.
     */
    public class GroupRep extends GroupBase {
        /**
         *
         * @param name is the string key of this group repeat
         * @param group is the base CDI representation
         * @param segment is the memory space number
         * @param origin is the address of this repeat in that memory space (All skips are
         *               already performed)
         * @param index is the 1-based index of this repeat of the given group
         */
        GroupRep(String name, CdiRep.Group group, int segment, int origin, int index) {
            super(name, group, segment, origin);
            size = processGroup(name, segment, group.getItems(), items, origin);
            this.index = index;
        }
        // The 1-based index of this replica.
        public int index;
    }

    /**
     * Represents the root entry of a group. If the group is repeated, the children will be the
     * individual repeats. If the group is not repeated, the children will be the members in this
     * group.
     */
    public class GroupEntry extends GroupBase {
        /**
         * @param baseName is the string key of this group (including the name of the current group)
         * @param group is the CDI representation
         * @param segment is the memory space number
         * @param origin is the address of this repeat in that memory space (all skips are
         *               already performed)
         */
        GroupEntry(String baseName, CdiRep.Group group, int segment, int origin) {
            super(baseName, group, segment, origin);
            if (group.getReplication() <= 1) {
                size = processGroup(baseName, segment, group.getItems(), this.items, this.origin);
            } else {
                size = 0;
                for (int i = 0; i < group.getReplication(); ++i) {
                    CdiEntry e = new GroupRep(baseName + "(" + i + ")", group, segment, origin,
                            i + 1);
                    items.add(e);
                    origin += e.size;
                    size += e.size;
                }
            }
        }
    }

    /**
     * Represents an integer variable.
     */
    public class IntegerEntry extends CdiEntry {
        public CdiRep.IntegerRep rep;

        IntegerEntry(String name, CdiRep.IntegerRep rep, int segment, int origin) {
            this.key = name;
            this.space = segment;
            this.origin = origin;
            this.rep = rep;
            this.size = rep.getSize();
        }

        @Override
        public CdiRep.Item getCdiItem() {
            return rep;
        }

        public long getValue() {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = cache.read(origin, size);
            if (b == null) return 0;
            long ret = 0;
            for (int i = 0; i < b.length; ++i) {
                ret <<= 8;
                int p = b[i];
                if (p < 0) p += 128;
                ret |= p;
            }
            return ret;
        }

        public void setValue(long value) {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = new byte[size];
            for (int i = size - 1; i >= 0; --i) {
                b[i] = (byte)(value & 0xff);
                value >>= 8;
            }
            cache.write(origin, b);
        }
    }

    /**
     * Represents an event variable.
     */
    public class EventEntry extends CdiEntry {
        public CdiRep.EventID rep;

        EventEntry(String name, CdiRep.EventID rep, int segment, int origin) {
            this.key = name;
            this.space = segment;
            this.origin = origin;
            this.rep = rep;
            this.size = 8;
        }

        @Override
        public CdiRep.Item getCdiItem() {
            return rep;
        }

        public EventID getValue() {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = cache.read(origin, size);
            if (b == null) return null;
            return new EventID(b);
        }

        public void setValue(EventID event) {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = event.getContents();
            if (b == null) return;
            cache.write(origin, b);
        }
    }

    /**
     * Represents a string variable.
     */
    public class StringEntry extends CdiEntry {
        public CdiRep.StringRep rep;

        StringEntry(String name, CdiRep.StringRep rep, int segment, int origin) {
            this.key = name;
            this.space = segment;
            this.origin = origin;
            this.rep = rep;
            this.size = rep.getSize();
        }

        @Override
        public CdiRep.Item getCdiItem() {
            return rep;
        }

        public String getValue() {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = cache.read(origin, size);
            if (b == null) return null;
            // We search for a terminating null byte and clip the string there.
            int len = 0;
            while (len < b.length && b[len] != 0) ++len;
            byte[] rep = new byte[len];
            System.arraycopy(b, 0, rep, 0, len);
            String ret = new String(rep);
            return ret;
        }

        public void setValue(String value) {
            MemorySpaceCache cache = getCacheForSpace(space);
            byte[] b = new byte[size];
            byte[] f;
            try {
                f = value.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) { return; }
            System.arraycopy(f, 0, b, 0, Math.min(f.length, b.length - 1));
            cache.write(this.origin, b);
        }
    }

}