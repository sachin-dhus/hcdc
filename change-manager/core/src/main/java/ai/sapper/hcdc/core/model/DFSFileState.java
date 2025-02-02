package ai.sapper.hcdc.core.model;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DFSFileState {
    private long id;
    private String zkPath;
    private String hdfsFilePath;
    private long createdTime;
    private long updatedTime;
    private long numBlocks;
    private long blockSize;
    private long dataSize;
    private long lastTnxId;
    private long timestamp;
    private EFileType fileType = EFileType.UNKNOWN;
    private String storagePath;

    private EFileState state = EFileState.Unknown;

    private List<DFSBlockState> blocks;

    public DFSFileState add(@NonNull DFSBlockState block) {
        if (blocks == null)
            blocks = new ArrayList<>();
        blocks.add(block);
        numBlocks++;

        return this;
    }

    public DFSBlockState get(long blockId) {
        if (blocks != null && !blocks.isEmpty()) {
            for (DFSBlockState bs : blocks) {
                if (bs.getBlockId() == blockId) {
                    return bs;
                }
            }
        }
        return null;
    }

    public List<DFSBlockState> sortedBlocks() {
        if (blocks != null && !blocks.isEmpty()) {
            blocks.sort(new DFSBlockComparator());
        }
        return blocks;
    }

    public boolean checkDeleted() {
        return (state == EFileState.Deleted);
    }

    public boolean hasError() {
        return (state == EFileState.Error);
    }

    public boolean canProcess() {
        return (!hasError() && !checkDeleted());
    }

    public boolean canUpdate() {
        if (canProcess()) {
            return (state == EFileState.New || state == EFileState.Updating);
        }
        return false;
    }

    public boolean hasBlocks() {
        return (blocks != null && !blocks.isEmpty());
    }
}
