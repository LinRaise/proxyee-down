package lee.study.down.model;

import io.netty.channel.Channel;
import java.io.Serializable;
import java.nio.channels.SeekableByteChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectInfo implements Serializable {

  private static final long serialVersionUID = 231649750985691346L;
  private long startPosition;
  private long endPosition;
  private long downSize;
  private int status = 0;
  private long lastActionTime;
  private long errorCount = 0;
  private int chunkIndex;


  private Channel connectChannel;
  private SeekableByteChannel fileChannel;

  public long getTotalSize() {
    return endPosition - startPosition + 1;
  }
}
