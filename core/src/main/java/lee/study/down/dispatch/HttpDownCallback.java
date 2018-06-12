package lee.study.down.dispatch;

import lee.study.down.model.ChunkInfo;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.TaskInfo;

public class HttpDownCallback {

  public void onStart(HttpDownInfo httpDownInfo) {
  }

  public void onProgress(HttpDownInfo httpDownInfo, TaskInfo taskInfo) {
  }

  public void onPause(HttpDownInfo httpDownInfo, TaskInfo taskInfo) {
  }

  public void onContinue(HttpDownInfo httpDownInfo, TaskInfo taskInfo) {
  }

  public void onChunkError(HttpDownInfo httpDownInfo, TaskInfo taskInfo, ChunkInfo chunkInfo) {
  }

  public void onError(HttpDownInfo httpDownInfo, TaskInfo taskInfo) {
  }

  public void onChunkDone(HttpDownInfo httpDownInfo, TaskInfo taskInfo, ChunkInfo chunkInfo) {
  }

  public void onDone(HttpDownInfo httpDownInfo, TaskInfo taskInfo) {
  }
}
