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
    private static final String STREAM_ID = "read:tita3037207";
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