// PlayerViewModel.java（完整版）
package de.kai_morich.simple_usb_terminal;

import android.app.Application;
import android.net.Uri;
import androidx.lifecycle.AndroidViewModel;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import io.github.thibaultbee.srtdroid.core.enums.SockOpt;
import io.github.thibaultbee.srtdroid.core.enums.Transtype;
import io.github.thibaultbee.srtdroid.core.models.SrtSocket;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.SeekParameters;

public class PlayerViewModel extends AndroidViewModel {
    private ExoPlayer exoPlayer;
    private static final String SRT_HOST = "119.23.220.15";
    private static final int SRT_PORT = 8890;
    private static final String STREAM_ID = "read:ao2car3037207";
    private static final int PAYLOAD_SIZE = 1316;

    public PlayerViewModel(Application application) {
        super(application);
        initializePlayer();
    }

    private String getLoginAccount() {
        SharedPreferences sharedPreferences = getApplication()  // 通过AndroidViewModel的getApplication()
                .getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        return sharedPreferences.getString("login_account", "default_account");
    }

    private String getStreamId() {
        return "read:" + getLoginAccount();
    }

    private void initializePlayer() {
        // 配置硬件解码
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getApplication())
                .setEnableDecoderFallback(false)  // 启用解码器回退机制
                .setMediaCodecSelector(MediaCodecSelector.DEFAULT);  // 使用默认的硬件编解码器

        // 零缓冲配置
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1, 1, 0, 0)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        exoPlayer = new ExoPlayer.Builder(getApplication())
                .setRenderersFactory(renderersFactory)  // 注入硬件解码配置
                .setLoadControl(loadControl)
                .build();
        
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC);
    }

    public ExoPlayer getPlayer() {
        return exoPlayer;
    }

    public void playHttpStream(String httpUrl) {
        // 使用ExoPlayer内置的HTTP数据源
        ProgressiveMediaSource.Factory factory = 
            new ProgressiveMediaSource.Factory(
                new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
            );
        
        exoPlayer.setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.parse(httpUrl))));
        exoPlayer.prepare();
    }

    public void playStream(Uri uri) {

        // 解析自定义SRT URI
        if ("srt".equals(uri.getScheme())) {
            String host = uri.getHost();
            int port = uri.getPort();
            String streamId = uri.getQueryParameter("streamid");
            
            if (host != null && port != -1) {
                playCustomSrtStream(host, port, streamId != null ? streamId : getStreamId());
                return;
            }
        }
        
        // 其他URI处理（如http/https）
        playHttpStream(uri.toString());
    }

    /**
     * 自定义SRT流播放（新增内部方法）
     */
    private void playCustomSrtStream(String host, int port, String streamId) {
        ProgressiveMediaSource.Factory factory = 
            new ProgressiveMediaSource.Factory(new CustomSrtDataSourceFactory(host, port, streamId));
        exoPlayer.setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.EMPTY)));
        exoPlayer.prepare();
    }

    // 自定义SRT数据源工厂（新增内部类）
    private static class CustomSrtDataSourceFactory implements DataSource.Factory {
        private final String host;
        private final int port;
        private final String streamId;

        CustomSrtDataSourceFactory(String host, int port, String streamId) {
            this.host = host;
            this.port = port;
            this.streamId = streamId;
        }

        @Override
        public DataSource createDataSource() {
            return new CustomSrtDataSource(host, port, streamId);
        }
    }

    private static class CustomSrtDataSource extends BaseDataSource {
        private final String host;
        private final int port;
        private final String streamId;
        private SrtSocket srtSocket;
        private Queue<Byte> byteQueue = new LinkedList<>();

        CustomSrtDataSource(String host, int port, String streamId) {
            super(true); // true表示是streaming数据源
            this.host = host;
            this.port = port;
            this.streamId = streamId;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            try {
                srtSocket = new SrtSocket();
                srtSocket.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE);
                srtSocket.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE);
                srtSocket.setSockFlag(SockOpt.STREAMID, streamId);
                srtSocket.setSockFlag(SockOpt.RCVLATENCY, 0);
                srtSocket.connect(host, port);
                return C.LENGTH_UNSET;
            } catch (Exception e) {
                throw new IOException("SRT连接失败: " + e.getMessage());
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            
            byte[] receivedData = srtSocket.recv(PAYLOAD_SIZE);
            for (byte b : receivedData) {
                byteQueue.add(b);
            }

            int bytesRead = 0;
            while (bytesRead < length && !byteQueue.isEmpty()) {
                buffer[offset + bytesRead] = byteQueue.poll();
                bytesRead++;
            }
            return bytesRead;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("srt://" + host + ":" + port);
        }

        @Override
        public void close() {
            try {
                if (srtSocket != null) {
                    srtSocket.close();
                }
            } catch (Exception e) {
                Log.e("SRT", "关闭SRT连接错误: " + e.getMessage());
            }
            byteQueue.clear();
        }
    }

    public void setMediaItem(String srtUrl) {
        // 创建 SRT 数据源
        ProgressiveMediaSource.Factory factory = 
            new ProgressiveMediaSource.Factory(new SrtDataSourceFactory());
        exoPlayer.setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.EMPTY)));
        exoPlayer.prepare();
    }

    // SRT 数据源实现
    private class SrtDataSource extends BaseDataSource {
        private SrtSocket srtSocket;
        private Queue<Byte> byteQueue = new LinkedList<>();

        public SrtDataSource() {
            super(true);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            try {
                srtSocket = new SrtSocket();
                srtSocket.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE);
                srtSocket.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE);
                srtSocket.setSockFlag(SockOpt.STREAMID, getStreamId());
                srtSocket.setSockFlag(SockOpt.RCVLATENCY, 0);
                srtSocket.setSockFlag(SockOpt.TSBPDMODE, true);
                srtSocket.setSockFlag(SockOpt.NAKREPORT, true);
                srtSocket.connect(SRT_HOST, SRT_PORT);
                return C.LENGTH_UNSET;
            } catch (Exception e) {
                throw new IOException("SRT连接失败: " + e.getMessage());
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            
            // 直接接收字节数组
            byte[] receivedData = srtSocket.recv(PAYLOAD_SIZE);

            // 填充队列
            for (byte b : receivedData) {
                byteQueue.add(b);
            }

            // 读取数据到缓冲区
            int bytesRead = 0;
            while (bytesRead < length && !byteQueue.isEmpty()) {
                buffer[offset + bytesRead] = byteQueue.poll();
                bytesRead++;
            }
            return bytesRead;
        }

        @Override
        public Uri getUri() {
            return Uri.parse("srt://" + SRT_HOST + ":" + SRT_PORT);
        }

        @Override
        public void close() {
            try {
                if (srtSocket != null) {
                    srtSocket.close();
                }
            } catch (Exception e) {
                Log.e("SRT", "关闭错误: " + e.getMessage());
            }
            byteQueue.clear();
        }
    }

    // 数据源工厂
    private class SrtDataSourceFactory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new SrtDataSource();
        }
    }
}