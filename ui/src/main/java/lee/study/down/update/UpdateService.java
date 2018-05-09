package lee.study.down.update;

import io.netty.channel.nio.NioEventLoopGroup;
import lee.study.down.boot.HttpDownBootstrap;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.model.UpdateInfo;

public interface UpdateService {

  NioEventLoopGroup clientLoopGroup = new NioEventLoopGroup(1);

  UpdateInfo check(float currVersion) throws Exception;

  HttpDownBootstrap update(UpdateInfo updateInfo, HttpDownCallback callback) throws Exception;
}
