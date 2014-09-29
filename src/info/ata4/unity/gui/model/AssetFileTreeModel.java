/*
 ** 2014 September 22
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.gui.model;

import info.ata4.log.LogUtils;
import info.ata4.unity.asset.AssetFile;
import info.ata4.unity.asset.ObjectPath;
import info.ata4.unity.assetbundle.AssetBundleReader;
import info.ata4.unity.assetbundle.AssetBundleUtils;
import info.ata4.unity.assetbundle.BufferedEntry;
import info.ata4.unity.assetbundle.StreamedEntry;
import info.ata4.unity.rtti.FieldNode;
import info.ata4.unity.rtti.ObjectData;
import info.ata4.unity.rtti.RuntimeTypeException;
import java.awt.Cursor;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import org.apache.commons.lang3.StringUtils;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetFileTreeModel extends DefaultTreeModel implements TreeWillExpandListener {
    
    private static final Logger L = LogUtils.getLogger();
    
    private final Set<DefaultMutableTreeNode> unloadedObjectDataNodes = new HashSet<>();
    private final Set<DefaultMutableTreeNode> unloadedAssetBundleEntryNodes = new HashSet<>();
    
    private final Window window;
    private DefaultMutableTreeNode rootNode;

    public AssetFileTreeModel(Window parent, Path file) throws IOException {
        super(null);
        
        window = parent;
        
        try {
            rootNode = new DefaultMutableTreeNode(file);
            root = rootNode;

            busyState();
            
            if (AssetBundleUtils.isAssetBundle(file)) {
                try (AssetBundleReader assetBundle = new AssetBundleReader(file)) {
                    addAssetBundle(rootNode, assetBundle);
                }
            } else {
                AssetFile asset = new AssetFile();
                asset.load(file);

                addAsset(rootNode, asset);
            }
        } finally {
            idleState();
        }
    }
    
    private void busyState() {
        window.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }
    
    private void idleState() {
        window.setCursor(Cursor.getDefaultCursor());
    }
    
    private void addAssetBundle(DefaultMutableTreeNode root, AssetBundleReader assetBundle) throws IOException {
        for (StreamedEntry entry : assetBundle) {
            DefaultMutableTreeNode current = root;

            // create folders in case the name contains path separators
            String[] parts = StringUtils.split(entry.getInfo().getName(), '/');
            for (int i = 0; i < parts.length - 1; i++) {
                DefaultMutableTreeNode folderNode = null;
                String folderName = parts[i];

                // look for existing folder node
                for (int j = 0; j < current.getChildCount(); j++) {
                    DefaultMutableTreeNode child = ((DefaultMutableTreeNode) current.getChildAt(j));
                    if (child.getUserObject().equals(folderName)) {
                        folderNode = child;
                        break;
                    }
                }

                // create and add folder node if required
                if (folderNode == null) {
                    folderNode = new DefaultMutableTreeNode(folderName);
                    current.add(folderNode);
                }

                // move one level up
                current = folderNode;
            }

            DefaultMutableTreeNode entryNode = new DefaultMutableTreeNode(entry.buffer());
            if (entry.getInfo().isAsset()) {
                entryNode.add(new DefaultMutableTreeNode());
                unloadedAssetBundleEntryNodes.add(entryNode);
            }

            current.add(entryNode);
        }
    }
    
    private void addAsset(DefaultMutableTreeNode root, AssetFile asset) {
        Map<String, DefaultMutableTreeNode> nodeCategories = new TreeMap<>();
        for (ObjectData objectData : asset.getObjects()) {
            try {
                String fieldNodeType = objectData.getTypeTree().getType().getTypeName();
                
                if (!nodeCategories.containsKey(fieldNodeType)) {
                    DefaultMutableTreeNode nodeCategory = new DefaultMutableTreeNode(fieldNodeType);
                    nodeCategories.put(fieldNodeType, nodeCategory);
                }
                
                DefaultMutableTreeNode objectDataNode = new DefaultMutableTreeNode(objectData);
                objectDataNode.add(new DefaultMutableTreeNode());
                unloadedObjectDataNodes.add(objectDataNode);
                nodeCategories.get(fieldNodeType).add(objectDataNode);
            } catch (RuntimeTypeException ex) {
                L.log(Level.WARNING, "Can't deserialize object " + objectData, ex);
                root.add(new DefaultMutableTreeNode(ex));
            }
        }
        
        for (DefaultMutableTreeNode treeNode : nodeCategories.values()) {
            root.add(treeNode);
        }
    }
    
    private DefaultMutableTreeNode convertNode(FieldNode fieldNode, ObjectPath path) {
        Object fieldValue = fieldNode.getValue();
        DefaultMutableTreeNode treeNode;
        
        if (fieldValue instanceof FieldNode) {
            treeNode = convertNode((FieldNode) fieldValue, path);
        } else if (fieldValue instanceof List) {
            List fieldList = (List) fieldValue;
            treeNode = new DefaultMutableTreeNode(fieldNode);
            
            for (Object item : fieldList) {
                if (item instanceof FieldNode) {
                    treeNode.add(convertNode((FieldNode) item, path));
                } else {
                    treeNode.add(new DefaultMutableTreeNode(item));
                }
            }
        } else {
            treeNode = new DefaultMutableTreeNode(fieldNode);
        }
        
        for (FieldNode childFieldNode : fieldNode) {
            treeNode.add(convertNode(childFieldNode, path));
        }
        
        return treeNode;
    }

    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        Object obj = event.getPath().getLastPathComponent();
        if (!(obj instanceof DefaultMutableTreeNode)) {
            return;
        }
        
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) obj;
        
        Object userObj = treeNode.getUserObject();
        if (unloadedAssetBundleEntryNodes.contains(treeNode)) {
            BufferedEntry entry = (BufferedEntry) userObj;
      
            // clear node
            treeNode.removeAllChildren();

            // load the asset
            L.log(Level.FINE, "Lazy-loading asset for entry {0}", entry);

            try {
                busyState();
                AssetFile asset = new AssetFile();
                asset.load(entry.getReader());
                addAsset(treeNode, asset);
            } catch (IOException ex) {
                L.log(Level.WARNING, "Can't load asset", ex);
                treeNode.add(new DefaultMutableTreeNode(ex));
            } finally {
                idleState();
            }
            
            unloadedAssetBundleEntryNodes.remove(treeNode);
        } else if (unloadedObjectDataNodes.contains(treeNode)) {
            ObjectData objectData = (ObjectData) userObj;
            ObjectPath objectPath = objectData.getPath();
            FieldNode fieldNode = objectData.getInstance();
            
            L.log(Level.FINE, "Lazy-loading object {0}", objectPath);
            
            treeNode.removeAllChildren();
            for (FieldNode childFieldNode : fieldNode) {
                treeNode.add(convertNode(childFieldNode, objectPath));
            }
            
            unloadedObjectDataNodes.remove(treeNode);
        }
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
    }
}
