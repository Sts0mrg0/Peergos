package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.*;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class FileTreeNode {

    public static final FileTreeNode ROOT = new FileTreeNode(null, null, Collections.EMPTY_SET, Collections.EMPTY_SET, null);
    RetrievedFilePointer pointer;
    Set<FileTreeNode> children = new HashSet<>();
    Map<String, FileTreeNode> childrenByName = new HashMap<>();
    String ownername;
    Set<String> readers;
    Set<String> writers;
    UserPublicKey entryWriterKey;

    public FileTreeNode(RetrievedFilePointer pointer, String ownername, Set<String> readers, Set<String> writers, UserPublicKey entryWriterKey) {
        this.pointer = pointer == null ? null : pointer.withWriter(entryWriterKey);
        this.ownername = ownername;
        this.readers = readers;
        this.writers = writers;
        this.entryWriterKey = entryWriterKey;
    }

    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileTreeNode))
            return false;
        return pointer.equals(((FileTreeNode)other).getPointer());
    }

    public boolean hasChildByName(String name) {
        return childrenByName.containsKey(name);
    }

    public RetrievedFilePointer getPointer() {
        return pointer;
    }

    public void addChild(FileTreeNode child) {
        String name = child.getFileProperties().name;
        if (childrenByName.containsKey(name)) {
            if (pointer != null) {
                throw new IllegalStateException("Child already exists with name: "+name);
            } else
                return;
        }
        children.add(child);
        childrenByName.put(name, child);
    }

    public Optional<FileTreeNode> getDescendentByPath(String path, UserContext context) {
        if (path.length() == 0)
            return Optional.of(this);
        if (path.startsWith("/"))
            path = path.substring(1);
        int slash = path.indexOf("/");
        String prefix = slash > 0 ? path.substring(0, slash) : path;
        String suffix = slash > 0 ? path.substring(slash + 1) : "";
        Set<FileTreeNode> children = getChildren(context);
        for (FileTreeNode child: children)
            if (child.getFileProperties().name.equals(prefix)) {
                return child.getDescendentByPath(suffix, context);
            }
        return Optional.empty();
    }

    public void removeChild(FileTreeNode child, UserContext context) {
        String name = child.getFileProperties().name;
        children.remove(childrenByName.remove(name));
        return pointer.fileAccess.removeChild(child.getPointer(), pointer.filePointer, context);
    }

    public boolean addLinkTo(FileTreeNode file, UserContext context) {
        if (!this.isDirectory())
            return false;
        if (!this.isWritable())
            return false;
        String name = file.getFileProperties().name;
        if (childrenByName.containsKey(name)) {
            System.out.println("Child already exists with name: "+name);
            return false;
        }
        Location loc = file.getLocation();
        if (file.isDirectory()) {
            pointer.fileAccess.addSubdir(loc, this.getKey(), file.getKey());
        } else {
            pointer.fileAccess.addFile(loc, this.getKey(), file.getKey());
        }
        this.addChild(file);
        return pointer.fileAccess.commit(pointer.filePointer.owner, entryWriterKey, pointer.filePointer.mapKey, context);
    }

    public boolean isLink() {
        return pointer.fileAcess.isLink();
    }

    public String toLink() {
        return pointer.filePointer.toLink();
    }

    public boolean isWritable() {
        return entryWriterKey instanceof User;
    }

    public SymmetricKey getKey() {
        return pointer.filePointer.baseKey;
    }

    public Location getLocation() {
        return new Location(pointer.filePointer.owner, pointer.filePointer.writer, pointer.filePointer.mapKey);
    }

    public Set<Location> getChildrenLocations() {
        if (!this.isDirectory())
            return Collections.EMPTY_SET;
        return pointer.fileAccess.getChildrenLocations(pointer.filePointer.baseKey);
    }

    public void clear() {
        children.clear();
        childrenByName.clear();
    }

    public Optional<FileTreeNode> retrieveParent(UserContext context) {
        if (pointer == null)
            return Optional.empty();
        SymmetricKey parentKey = getParentKey();
        RetrievedFilePointer parentRFP = pointer.fileAccess.getParent(parentKey, context);
        if (parentRFP == null)
            return Optional.of(FileTreeNode.ROOT);
        return Optional.of(new FileTreeNode(parentRFP, ownername, Collections.EMPTY_SET, Collections.EMPTY_SET, entryWriterKey));
    }

    public SymmetricKey getParentKey() {
        SymmetricKey parentKey = pointer.filePointer.baseKey;
        if (this.isDirectory())
            try {
                parentKey = pointer.fileAccess.getParentKey(parentKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return parentKey;
    }

    public Set<FileTreeNode> getChildren(UserContext context) {
        if (this == FileTreeNode.ROOT)
            return new HashSet<>(children);
        try {
            Set<RetrievedFilePointer> childrenRFPs = retrieveChildren(context);
            Set<FileTreeNode> newChildren = childrenRFPs.stream().map(x -> new FileTreeNode(x, ownername, readers, writers, entryWriterKey)).collect(Collectors.toSet());
            clear();
            newChildren.forEach(c -> addChild(c));
            return new HashSet<>(children);
        } catch (Exception e) {
            // directories we don't have read access to have children populated during tree creation
            return new HashSet<>(children);
        }
    }

    private Set<RetrievedFilePointer> retrieveChildren(UserContext context) {
        ReadableFilePointer filePointer = pointer.filePointer;
        FileAccess fileAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = filePointer.baseKey;
        boolean canGetChildren = true;
        try {
            fileAccess.getMetaKey(rootDirKey);
            canGetChildren = false;
        } catch (Exception e) {}
        if (canGetChildren)
            return fileAccess.getChildren(context, rootDirKey);
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    public String getOwner() {
        return ownername;
    }

    public boolean isDirectory() {
        return pointer.fileAccess.isDirectory();
    }

    public boolean uploadFile(String filename, File file, UserContext context, Consumer<Integer> monitor) {
        if (!this.isLegalName(filename))
            return false;
        if (childrenByName.containsKey(filename)) {
            System.out.println("Child already exists with name: "+filename);
            return false;
        }
        SymmetricKey fileKey = SymmetricKey.random();
        SymmetricKey rootRKey = pointer.filePointer.baseKey;
        UserPublicKey owner = pointer.filePointer.owner;
        ByteArrayWrapper dirMapKey = pointer.filePointer.mapKey;
        UserPublicKey writer = pointer.filePointer.writer;
        FileAccess dirAccess = pointer.fileAccess;
        Location parentLocation = new Location(owner, writer, dirMapKey);
        SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);

        byte[] thumbData = generateThumbnail(file, filename);
        FileProperties fileProps = new FileProperties(filename, file.length(), LocalDateTime.now(), 0, thumbData);
        FileUploader chunks = new FileUploader(filename, file, fileKey, parentLocation, dirParentKey, monitor, fileProps);
        Location fileLocation = chunks.upload(context, owner, entryWriterKey);
        dirAccess.addFile(fileLocation, rootRKey, fileKey);
        return context.uploadChunk(dirAccess, owner, entryWriterKey, dirMapKey);
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
    }

    public boolean mkdir(String newFolderName, UserContext context, SymmetricKey requestedBaseSymmetricKey, boolean isSystemFolder) {
        if (!this.isDirectory())
            return false;
        if (!isLegalName(newFolderName))
            return false;
        if (childrenByName.containsKey(newFolderName)) {
            System.out.println("Child already exists with name: "+newFolderName);
            return false;
        }
        ReadableFilePointer dirPointer = pointer.filePointer;
        FileAccess dirAccess = pointer.fileAccess;
        SymmetricKey rootDirKey = dirPointer.baseKey;
        return dirAccess.mkdir(newFolderName, context, entryWriterKey, dirPointer.mapKey, rootDirKey, requestedBaseSymmetricKey, isSystemFolder);
    }

    public boolean rename(String newName, UserContext context, FileTreeNode parent) {
        if (!this.isLegalName(newName))
            return false;
        if (parent != null && parent.hasChildByName(newName))
            return false;
        //get current props
        ReadableFilePointer filePointer = pointer.filePointer;
        SymmetricKey baseKey = filePointer.baseKey;
        FileAccess fileAccess = pointer.fileAccess;

        SymmetricKey key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
        FileProperties currentProps = fileAccess.getFileProperties(key);

        FileProperties newProps = new FileProperties(newName, currentProps.size, currentProps.modified, currentProps.attr, currentProps.thumbnail);

        return fileAccess.rename(writableFilePointer(), newProps, context);
    }

    private ReadableFilePointer writableFilePointer() {
        ReadableFilePointer filePointer = pointer.filePointer;
        FileAccess fileAccess = pointer.fileAccess;
        SymmetricKey baseKey = filePointer.baseKey;
        return new ReadableFilePointer(filePointer.owner, entryWriterKey, filePointer.mapKey, baseKey);
    }

    public UserPublicKey getEntryWriterKey() {
        return entryWriterKey;
    }

    public boolean copyTo(FileTreeNode target, UserContext context) {
        if (! target.isDirectory())
            throw new IllegalStateException("CopyTo target "+ target +" must be a directory");
        if (target.hasChildByName(getFileProperties().name))
            return false;
        //make new FileTreeNode pointing to the same file, but with a different location
        byte[] newMapKey = window.nacl.randomBytes(32);
        SymmetricKey ourBaseKey = this.getKey();
        // a file baseKey is the key for the chunk, which hasn't changed, so this must stay the same
        SymmetricKey newBaseKey = this.isDirectory() ? SymmetricKey.random() : ourBaseKey;
        ReadableFilePointer newRFP = new ReadableFilePointer(context.user, target.getEntryWriterKey(), newMapKey, newBaseKey);
        Location newParentLocation = target.getLocation();
        SymmetricKey newParentParentKey = target.getParentKey();

        FileAccess newAccess = pointer.fileAccess.copyTo(ourBaseKey, newBaseKey, newParentLocation, newParentParentKey, target.getEntryWriterKey(), newMapKey, context);
        // upload new metadatablob
        RetrievedFilePointer newRetrievedFilePointer = new RetrievedFilePointer(newRFP, newAccess);
        FileTreeNode newFileTreeNode = new FileTreeNode(newRetrievedFilePointer, context.username, [], [], target.getEntryWriterKey());
        return target.addLinkTo(newFileTreeNode, context);
    }

    public boolean remove(UserContext context, FileTreeNode parent) {
        var func = function() {
            if (parent != null)
                return parent.removeChild(this, context);
            return Promise.resolve(true);
        }.bind(this);
        return func().then(function(res) {
            return new RetrievedFilePointer(writableFilePointer(), pointer.fileAccess).remove(context);
        });
    }

    public InputStream getInputStream(UserContext context, size, Consumer<Long> monitor) {
        SymmetricKey baseKey = pointer.filePointer.baseKey;
        return pointer.fileAccess.retriever.getFile(context, baseKey, size, monitor)
    }

    public FileProperties getFileProperties() {
        if (pointer == null)
            return new FileProperties("/", 0, LocalDateTime.MIN, false, Optional.empty());
        SymmetricKey parentKey = this.getParentKey();
        return pointer.fileAccess.getFileProperties(parentKey);
    }
}