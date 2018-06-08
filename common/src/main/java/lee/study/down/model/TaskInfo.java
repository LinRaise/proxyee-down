package lee.study.down.model;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class TaskInfo implements Serializable {

  private static final long serialVersionUID = 4813413517396555930L;
  private String id;
  private String filePath;
  private String fileName;
  private int connections;
  private long totalSize;
  private boolean supportRange;
  private long downSize;
  private long startTime = 0;
  private int status;
  private long speed;
  private List<ChunkInfo> chunkInfoList;
  private List<ConnectInfo> connectInfoList;

  public String buildTaskFilePath() {
    return getFilePath() + File.separator + getFileName();
  }

  public String buildTaskRecordFilePath() {
    return getFilePath() + File.separator + "." + getFileName() + ".inf";
  }

  public String buildTaskRecordBakFilePath() {
    return getFilePath() + File.separator + "." + getFileName() + ".inf.bak";
  }

  public TaskInfo buildChunkInfoList() {
    List<ChunkInfo> chunkInfoList = new ArrayList<>();
    List<ConnectInfo> connectInfoList = new ArrayList<>();
    if (getTotalSize() > 0) {  //非chunked编码
      //计算chunk列表
      long chunkSize = getTotalSize() / getConnections();
      for (int i = 0; i < getConnections(); i++) {
        ChunkInfo chunkInfo = new ChunkInfo();
        ConnectInfo connectInfo = new ConnectInfo();
        chunkInfo.setIndex(i);
        long start = i * chunkSize;
        if (i == getConnections() - 1) { //最后一个连接去下载多出来的字节
          chunkSize += getTotalSize() % getConnections();
        }
        long end = start + chunkSize - 1;
        chunkInfo.setTotalSize(chunkSize);
        chunkInfoList.add(chunkInfo);

        connectInfo.setChunkIndex(i);
        connectInfo.setStartPosition(start);
        connectInfo.setEndPosition(end);
        connectInfoList.add(connectInfo);
      }
    } else { //chunked下载
      ChunkInfo chunkInfo = new ChunkInfo();
      ConnectInfo connectInfo = new ConnectInfo();
      connectInfo.setChunkIndex(0);
      chunkInfo.setIndex(0);
      chunkInfoList.add(chunkInfo);
      connectInfoList.add(connectInfo);
    }
    setChunkInfoList(chunkInfoList);
    setConnectInfoList(connectInfoList);
    return this;
  }

  public long getDownSize() {
    return chunkInfoList.stream()
        .mapToLong(ChunkInfo::getDownSize)
        .sum();
  }

  public long getSpeed() {
    return chunkInfoList.stream()
        .mapToLong(ChunkInfo::getSpeed)
        .sum();
  }

  public void reset() {
    startTime = 0;
    chunkInfoList.forEach(chunkInfo -> {
      chunkInfo.setStartTime(0);
      chunkInfo.setPauseTime(0);
      chunkInfo.setDownSize(0);
    });
  }
}
