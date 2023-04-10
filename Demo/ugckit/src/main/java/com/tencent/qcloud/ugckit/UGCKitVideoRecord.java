package com.tencent.qcloud.ugckit;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import android.media.AudioManager;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.tencent.liteav.demo.beauty.Beauty;
import com.tencent.liteav.demo.beauty.BeautyParams;
import com.tencent.liteav.demo.beauty.model.ItemInfo;
import com.tencent.liteav.demo.beauty.model.TabInfo;
import com.tencent.liteav.demo.beauty.view.BeautyPanel;
import com.tencent.qcloud.ugckit.basic.ITitleBarLayout;
import com.tencent.qcloud.ugckit.basic.OnUpdateUIListener;
import com.tencent.qcloud.ugckit.basic.UGCKitResult;
import com.tencent.qcloud.ugckit.component.dialog.ProgressDialogUtil;
import com.tencent.qcloud.ugckit.component.dialogfragment.ProgressFragmentUtil;
import com.tencent.qcloud.ugckit.module.ProcessKit;
import com.tencent.qcloud.ugckit.module.effect.VideoEditerSDK;
import com.tencent.qcloud.ugckit.module.effect.bgm.view.SoundEffectsPannel;
import com.tencent.qcloud.ugckit.module.record.AbsVideoRecordUI;
import com.tencent.qcloud.ugckit.module.record.MusicInfo;
import com.tencent.qcloud.ugckit.module.record.PhotoSoundPlayer;
import com.tencent.qcloud.ugckit.module.record.RecordBottomLayout;
import com.tencent.qcloud.ugckit.module.record.RecordModeView;
import com.tencent.qcloud.ugckit.module.record.RecordMusicManager;
import com.tencent.qcloud.ugckit.module.record.ScrollFilterView;
import com.tencent.qcloud.ugckit.module.record.TEChargePromptDialog;
import com.tencent.qcloud.ugckit.module.record.UGCKitRecordConfig;
import com.tencent.qcloud.ugckit.module.record.VideoRecordSDK;
import com.tencent.qcloud.ugckit.module.record.interfaces.IRecordButton;
import com.tencent.qcloud.ugckit.module.record.interfaces.IRecordMusicPannel;
import com.tencent.qcloud.ugckit.module.record.interfaces.IRecordRightLayout;
import com.tencent.qcloud.ugckit.module.record.AudioFocusManager;
import com.tencent.qcloud.ugckit.utils.BackgroundTasks;
import com.tencent.qcloud.ugckit.utils.DialogUtil;
import com.tencent.qcloud.ugckit.utils.LogReport;
import com.tencent.ugc.TXRecordCommon;
import com.tencent.ugc.TXUGCRecord;
import com.tencent.ugc.TXVideoEditConstants;
import com.tencent.ugc.TXVideoInfoReader;
import com.tencent.xmagic.XMagicImpl;
import com.tencent.xmagic.module.XmagicResParser;
import com.tencent.xmagic.panel.XmagicPanelDataManager;
import com.tencent.xmagic.telicense.TELicenseCheck;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UGCKitVideoRecord extends AbsVideoRecordUI implements
        IRecordRightLayout.OnItemClickListener,
        IRecordButton.OnRecordButtonListener,
        SoundEffectsPannel.SoundEffectsSettingPannelListener,
        IRecordMusicPannel.MusicChangeListener,
        ScrollFilterView.OnRecordFilterListener,
        VideoRecordSDK.OnVideoRecordListener {
    private static final String TAG = "UGCKitVideoRecord";

    private OnRecordListener      mOnRecordListener;
    private OnMusicChooseListener mOnMusicListener;
    private FragmentActivity      mActivity;
    private ProgressFragmentUtil  mProgressFragmentUtil;
    private ProgressDialogUtil    mProgressDialogUtil;
    private boolean               isInStopProcessing = false;
    private ExecutorService       videoProcessExecutor;

    private XMagicImpl             mXMagic;
    private XMagicImpl.XmagicState mXmagicState = XMagicImpl.XmagicState.IDLE;
    private volatile boolean       mIsTextureDestroyed = false;
    private boolean                mIsReleased = false;
    private AudioFocusManager      mAudioFocusManager = null;

    private int                    mBeautyType = -1;  //0 表示基础美颜 1、表示高级美颜
    private Fragment               mHostFragment;

    public UGCKitVideoRecord(Context context) {
        super(context);
        initDefault(context);
    }

    public UGCKitVideoRecord(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDefault(context);
    }

    public UGCKitVideoRecord(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initDefault(context);
    }

    private void initDefault(Context context) {
        mActivity = (FragmentActivity) getContext();
        // 初始化SDK:TXUGCRecord
        VideoRecordSDK.getInstance().initSDK();
        // 初始化视频草稿箱
        VideoRecordSDK.getInstance().initRecordDraft(context);
        VideoRecordSDK.getInstance().deleteAllParts();
        VideoRecordSDK.getInstance().setOnRestoreDraftListener(new VideoRecordSDK.OnRestoreDraftListener() {
            @Override
            public void onDraftProgress(long duration) {
                getRecordBottomLayout().updateProgress((int) duration);
                getRecordBottomLayout().getRecordProgressView().clipComplete();
            }

            @Override
            public void onDraftTotal(long duration) {
                getRecordRightLayout().setMusicIconEnable(false);
                getRecordRightLayout().setAspectIconEnable(false);

                float second = duration / 1000f;
                boolean enable = second >= UGCKitRecordConfig.getInstance().mMinDuration / 1000;
                getTitleBar().setVisible(enable, ITitleBarLayout.POSITION.RIGHT);
            }
        });

        VideoRecordSDK.getInstance().setVideoRecordListener(this);
        // 点击"下一步"
        getTitleBar().setVisible(false, ITitleBarLayout.POSITION.RIGHT);
        getTitleBar().setOnRightClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                //录制stop状态，由于stop的过程比较长，可长达一秒以上，做防重复点击的最小点击时间就需要设置的比较长。
                //使用录制的currentState状态来判断是否是STOP状态，虽然可以完美解决防重复点击问题，但是如果用户按返回回到该界面，
                //无法再次点击下一步，currentState状态仍然是stop。
                //所以这里采用一个新的布尔值进行限制
                if (isInStopProcessing) {
                    return;
                }
                isInStopProcessing = true;
                mProgressDialogUtil.showProgressDialog();

                VideoRecordSDK.getInstance().stopRecord();
            }
        });

        // 点击"右侧工具栏"（包括"美颜"，"音乐"，"音效"）
        getRecordRightLayout().setOnItemClickListener(this);
        getTEInfoImg().setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                TEChargePromptDialog.showTEConfirmDialog(mActivity);
            }
        });


        // 点击"录制按钮"（包括"拍照"，"单击拍"，"按住拍"）
        getRecordBottomLayout().setOnRecordButtonListener(this);
        getRecordBottomLayout().setOnDeleteLastPartListener(new RecordBottomLayout.OnDeleteLastPartListener() {
            @Override
            public void onUpdateTitle(boolean enable) {
                getTitleBar().setVisible(enable, ITitleBarLayout.POSITION.RIGHT);
                getRecordPauseSnapView().clearBitmap();
            }

            @Override
            public void onReRecord() {
                getRecordRightLayout().setMusicIconEnable(true);
                getRecordRightLayout().setAspectIconEnable(true);
            }
        });

        // 设置"音乐面板"监听器
        getRecordMusicPannel().setOnMusicChangeListener(this);
        // 设置"音效面板"监听器
        getSoundEffectPannel().setSoundEffectsSettingPannelListener(this);

        getScrollFilterView().setOnRecordFilterListener(this);

        mProgressDialogUtil = new ProgressDialogUtil(mActivity);

        UGCKitRecordConfig config = UGCKitRecordConfig.getInstance();
        // 初始化默认配置
        VideoRecordSDK.getInstance().initConfig(config);
        getBeautyPanel().setOnFilterChangeListener(new Beauty.OnFilterChangeListener() {

            @Override
            public void onChanged(Bitmap filterImage, int index) {
                if (mBeautyType == 0) {
                    getScrollFilterView().doTextAnimator(index);
                }
            }
        });
        getBeautyPanel().setOnBeautyListener(new BeautyPanel.OnBeautyListener() {
            public void onTabChange(TabInfo tabInfo, int position) {

            }

            @Override
            public boolean onClose() {
                getBeautyPanel().setVisibility(View.GONE);
                getRecordMusicPannel().setVisibility(View.GONE);
                getSoundEffectPannel().setVisibility(View.GONE);

                getRecordBottomLayout().setVisibility(View.VISIBLE);
                getRecordRightLayout().setVisibility(View.VISIBLE);
                return true;
            }

            @Override
            public boolean onClick(TabInfo tabInfo, int tabPosition, ItemInfo itemInfo, int itemPosition) {
                return false;
            }

            @Override
            public boolean onLevelChanged(TabInfo tabInfo, int tabPosition, ItemInfo itemInfo,
                                          int itemPosition, int beautyLevel) {
                return false;
            }
        });
        getScrollFilterView().setScrollable(false);

        mAudioFocusManager = new AudioFocusManager(getContext(), new AudioFocusManager.OnAudioFocusChangeListener() {

            @Override
            public void onLossFocus() {
                VideoRecordSDK.getInstance().pauseRecord();
            }

            @Override
            public void onGain(boolean lossTransient, boolean lossTransientCanDuck) {

            }
        });

        registerVideoProcessListener();
        XMagicImpl.checkAuth(new TELicenseCheck.TELicenseCheckListener() {
            @Override
            public void onLicenseCheckFinish(int errorCode, String msg) {
                if (errorCode == TELicenseCheck.ERROR_OK) {
                    loadXmagicRes();
                } else {
                    Log.e(TAG, "auth fail ，please check auth url and key" + errorCode + " " + msg);
                }
            }
        });
    }


    @Override
    public void setOnRecordListener(OnRecordListener listener) {
        mOnRecordListener = listener;
    }

    @Override
    public void setOnMusicChooseListener(OnMusicChooseListener listener) {
        mOnMusicListener = listener;
    }

    @Override
    public void start() {
        mIsTextureDestroyed = false;
        // 打开录制预览界面
        VideoRecordSDK.getInstance().startCameraPreview(getRecordVideoView());
        if (mXmagicState == XMagicImpl.XmagicState.STOPPED) {
            initXMagic();
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop");
        isInStopProcessing = false;
        if (mXMagic != null) {
            mXMagic.onPause();
            mXmagicState = XMagicImpl.XmagicState.STOPPED;
        }

        getRecordBottomLayout().getRecordButton().pauseRecordAnim();
        getRecordBottomLayout().closeTorch();
        // 停止录制预览界面
        VideoRecordSDK.getInstance().stopCameraPreview();
        // 暂停录制
        VideoRecordSDK.getInstance().pauseRecord();
    }

    @Override
    public void release() {
        Log.d(TAG, "release");
        XmagicPanelDataManager.getInstance().clearData();
        getRecordBottomLayout().getRecordProgressView().release();
        cleanBaseBeauty();
        // 停止录制
        VideoRecordSDK.getInstance().releaseRecord();

        UGCKitRecordConfig.getInstance().clear();
        VideoRecordSDK.getInstance().setVideoRecordListener(null);
        getBeautyPanel().setOnFilterChangeListener(null);
        ProcessKit.getInstance().setOnUpdateUIListener(null);
        VideoRecordSDK.getInstance().setOnRestoreDraftListener(null);
        mIsReleased = true;
        unRegisterVideoProcessListener();
    }

    @Override
    public void screenOrientationChange() {
        Log.d(TAG, "screenOrientationChange");
        VideoRecordSDK.getInstance().stopCameraPreview();

        VideoRecordSDK.getInstance().pauseRecord();

        VideoRecordSDK.getInstance().startCameraPreview(getRecordVideoView());
    }

    @Override
    public void setRecordMusicInfo(@NonNull MusicInfo musicInfo) {
        if (musicInfo != null) {
            Log.d(TAG, "music name:" + musicInfo.name + ", path:" + musicInfo.path);
        }
        getRecordBottomLayout().setVisibility(View.INVISIBLE);
        getRecordRightLayout().setVisibility(View.INVISIBLE);

        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            long duration = record.setBGM(musicInfo.path);
            musicInfo.duration = duration;
            Log.d(TAG, "music duration:" + musicInfo.duration);
        }
        // 设置音乐信息
        RecordMusicManager.getInstance().setRecordMusicInfo(musicInfo);
        mXMagic.setAudioMute(true);
        // 更新音乐Pannel
        getRecordMusicPannel().setMusicInfo(musicInfo);
        getRecordMusicPannel().setVisibility(View.VISIBLE);

        // 音乐试听
        RecordMusicManager.getInstance().startPreviewMusic();
    }

    @Override
    public void backPressed() {
        Log.d(TAG, "backPressed");
        // 录制已停止，则回调"录制被取消"
        if (VideoRecordSDK.getInstance().getRecordState() == VideoRecordSDK.STATE_STOP) {
            if (mOnRecordListener != null) {
                mOnRecordListener.onRecordCanceled();
            }
            return;
        }
        // 录制已开始，点击返回键，暂停录制
        if (VideoRecordSDK.getInstance().getRecordState() == VideoRecordSDK.STATE_START) {
            //相当于点击了暂停按钮
            getRecordBottomLayout().getRecordButton().pauseRecordAnim();
        }

        int size = VideoRecordSDK.getInstance().getPartManager().getPartsPathList().size();
        if (size == 0) {
            if (mOnRecordListener != null) {
                mOnRecordListener.onRecordCanceled();
            }
            return;
        }

        showGiveupRecordDialog();
    }

    /**
     * 显示放弃录制对话框
     */
    private void showGiveupRecordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        AlertDialog alertDialog = builder.setTitle(getResources().getString(R.string.ugckit_cancel_record)).setCancelable(false).setMessage(R.string.ugckit_confirm_cancel_record_content)
                .setPositiveButton(R.string.ugckit_give_up, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(@NonNull DialogInterface dialog, int which) {
                        dialog.dismiss();

                        VideoRecordSDK.getInstance().deleteAllParts();

                        if (mOnRecordListener != null) {
                            mOnRecordListener.onRecordCanceled();
                        }
                        return;
                    }
                })
                .setNegativeButton(getResources().getString(R.string.ugckit_wrong_click), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(@NonNull DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }).create();
        alertDialog.show();
    }

    /**
     * 点击录制开始按钮
     */
    @Override
    public void onRecordStart() {
        getRecordRightLayout().setVisibility(View.INVISIBLE);
        getRecordBottomLayout().startRecord();
        // 开始录制后不能再选择音乐
        getRecordRightLayout().setMusicIconEnable(false);
        // 开始录制后不能切换屏比
        getRecordRightLayout().setAspectIconEnable(false);

        // 对齐抖音，有BGM时候，录制为静音
        if (RecordMusicManager.getInstance().isChooseMusic()) {
            VideoRecordSDK.getInstance().getRecorder().setMicVolume(0);
        } else {
            VideoRecordSDK.getInstance().getRecorder().setMicVolume(mMicVolume);
        }

        // 开始/继续录制
        int retCode = VideoRecordSDK.getInstance().startRecord();
        if (retCode == VideoRecordSDK.START_RECORD_FAIL) { //点击开始录制失败，录制按钮状态变为暂停
            getRecordBottomLayout().getRecordButton().pauseRecordAnim();
            return;
        }
        if (mAudioFocusManager != null) {
            mAudioFocusManager.requestAudioFocus(AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        getRecordPauseSnapView().clearBitmap();
    }

    /**
     * 点击录制暂停按钮
     */
    @Override
    public void onRecordPause() {
        Log.d(TAG, "onRecordPause");

        if(UGCKitRecordConfig.getInstance().mPauseSnapOpacity > 0) {
            getRecordPauseSnapView().catchPauseImage();
        }

        getRecordRightLayout().setVisibility(View.VISIBLE);
        getRecordBottomLayout().pauseRecord();

        VideoRecordSDK.getInstance().pauseRecord();
        RecordMusicManager.getInstance().pauseMusic();

        if (mAudioFocusManager != null) {
            mAudioFocusManager.abandonAudioFocus();
        }
    }

    /**
     * 点击照相
     */
    @Override
    public void onTakePhoto() {
        PhotoSoundPlayer.playPhotoSound();

        VideoRecordSDK.getInstance().takePhoto(new RecordModeView.OnSnapListener() {
            @Override
            public void onSnap(Bitmap bitmap) {
                getSnapshotView().showSnapshotAnim(bitmap);
            }
        });
    }

    @Override
    public void onDeleteParts(int partsSize, long duration) {

    }

    @Override
    public void onShowBeautyPanel() {
        // 隐藏底部工具栏
        getRecordBottomLayout().setVisibility(View.GONE);
        // 隐藏右侧工具栏
        getRecordRightLayout().setVisibility(View.GONE);
        // 显示美颜Panel
        getBeautyPanel().setVisibility(View.VISIBLE);
        if (getBeautyPanel().getmTxBeautyManager() == null) {
            TXUGCRecord txugcRecord = VideoRecordSDK.getInstance().getRecorder();
            getBeautyPanel().setBeautyManager(txugcRecord.getBeautyManager());
            //设置默认美颜项
            VideoRecordSDK.getInstance().updateBeautyParam(new BeautyParams());
        }
        //恢复基础美颜属性
        getBeautyPanel().restoreBeauty();
        mBeautyType = 0;
        if (mXMagic != null) {
            mXMagic.setAudioMute(true);
        }
        getScrollFilterView().setScrollable(true);
    }

    @Override
    public void onShowTEBeautyPanel() {
        // 隐藏底部工具栏
        getRecordBottomLayout().setVisibility(View.GONE);
        // 隐藏右侧工具栏
        getRecordRightLayout().setVisibility(View.GONE);
        // 显示美颜Panel
        getTEPanel().setVisibility(View.VISIBLE);
        getTEInfoImg().setVisibility(VISIBLE);
        if (mXMagic != null) {
            mXMagic.setBeautyStateOpen();
            //如果设置了BGM,在打开高级美颜的动效时进行静音处理
            mXMagic.setAudioMute(RecordMusicManager.getInstance().isChooseMusic());
        }
        mBeautyType = 1;
        cleanBaseBeauty();
        getScrollFilterView().setScrollable(false);
        TEChargePromptDialog.showTETipDialog(mActivity);

    }


    private void cleanBaseBeauty() {
        //清空基础美颜效果
        BeautyParams baseBeautyParams = new BeautyParams();
        baseBeautyParams.mBeautyStyle = 0;
        baseBeautyParams.mBeautyLevel = 0;
        baseBeautyParams.mWhiteLevel = 0;
        baseBeautyParams.mFilterBmp = null;
        VideoRecordSDK.getInstance().updateBeautyParam(baseBeautyParams);
    }

    /**
     * 点击工具栏按钮"音乐"
     */
    @Override
    public void onShowMusicPanel() {
        boolean isChooseMusicFlag = RecordMusicManager.getInstance().isChooseMusic();
        if (isChooseMusicFlag) {
            // 隐藏底部工具栏
            getRecordBottomLayout().setVisibility(View.GONE);
            // 隐藏右侧工具栏
            getRecordRightLayout().setVisibility(View.GONE);
            // 显示音乐Pannel
            getRecordMusicPannel().setVisibility(View.VISIBLE);

            RecordMusicManager.getInstance().startMusic();
        } else {
            if (mOnMusicListener != null) {
                mOnMusicListener.onChooseMusic(UGCKitRecordConfig.getInstance().musicInfo.position);
            }
        }
    }

    @Override
    public void onShowSoundEffectPanel() {
        // 隐藏底部工具栏
        getRecordBottomLayout().setVisibility(View.GONE);
        // 隐藏右侧工具栏
        getRecordRightLayout().setVisibility(View.GONE);
        // 显示音效Pannel
        getSoundEffectPannel().setVisibility(View.VISIBLE);
    }

    @Override
    public void onAspectSelect(int aspectType) {
        UGCKitRecordConfig.getInstance().mAspectRatio = aspectType;
        VideoRecordSDK.getInstance().updateAspectRatio();
    }

    /************************************   音效Pannel回调接口 Begin  ********************************************/
    private float mMicVolume = 1.0f;
    @Override
    public void onMicVolumeChanged(float volume) {
        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            record.setMicVolume(volume);
        }
    }

    @Override
    public void onClickVoiceChanger(int type) {
        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            record.setVoiceChangerType(type);
        }
    }

    @Override
    public void onClickReverb(int type) {
        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            record.setReverb(type);
        }
    }

    /************************************   音效Pannel回调接口 End    ********************************************/

    /************************************   音乐Pannel回调接口 Begin  ********************************************/
    @Override
    public void onMusicVolumChanged(float volume) {
        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            record.setBGMVolume(volume);
        }
    }

    /**
     * 背景音乐裁剪
     *
     * @param startTime
     * @param endTime
     */
    @Override
    public void onMusicTimeChanged(long startTime, long endTime) {
        MusicInfo musicInfo = RecordMusicManager.getInstance().getMusicInfo();
        musicInfo.startTime = startTime;
        musicInfo.endTime = endTime;

        RecordMusicManager.getInstance().startPreviewMusic();
    }

    /**
     * 点击"音乐Pannel"的确定</p>
     * 1、关闭音乐Pannel</p>
     * 2、停止音乐试听
     */
    @Override
    public void onMusicSelect() {
        getRecordBottomLayout().setVisibility(View.VISIBLE);
        getRecordRightLayout().setVisibility(View.VISIBLE);
        // 录制添加BGM后是录制不了人声的，而音效是针对人声有效的
        getRecordRightLayout().setSoundEffectsEnabled(false);

        getRecordMusicPannel().setVisibility(View.GONE);

        // 停止音乐试听
        RecordMusicManager.getInstance().stopPreviewMusic();
    }

    /**
     * 点击"音乐Pannel"的切换音乐
     */
    @Override
    public void onMusicReplace() {
        if (mOnMusicListener != null) {
            mOnMusicListener.onChooseMusic(UGCKitRecordConfig.getInstance().musicInfo.position);
        }
    }

    /**
     * 点击"音乐Pannel"删除背景音乐
     */
    @Override
    public void onMusicDelete() {
        showDeleteMusicDialog();
    }

    private void showDeleteMusicDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        AlertDialog alertDialog = builder.setTitle(getResources().getString(R.string.ugckit_tips)).setCancelable(false).setMessage(R.string.ugckit_delete_bgm_or_not)
                .setPositiveButton(R.string.ugckit_confirm_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(@NonNull DialogInterface dialog, int which) {
                        dialog.dismiss();

                        RecordMusicManager.getInstance().deleteMusic();
                        if (mXMagic != null && mBeautyType == 1) {  //当删除背景音乐的时候进行判断，如果当前是在高级美颜中，则消除静音
                            mXMagic.setAudioMute(false);
                        }
                        // 录制添加BGM后是录制不了人声的，而音效是针对人声有效的
                        getRecordRightLayout().setSoundEffectIconEnable(true);

//                        getRecordMusicPannel().setMusicName("");
                        getRecordMusicPannel().setVisibility(View.GONE);
                    }
                })
                .setNegativeButton(getResources().getString(R.string.ugckit_btn_cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(@NonNull DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }).create();
        alertDialog.show();
    }

    /************************************   音乐Pannel回调接口 End    ********************************************/

    @Override
    public void onSingleClick(float x, float y) {
        getBeautyPanel().setVisibility(View.GONE);
        getTEPanel().setVisibility(View.GONE);
        getTEInfoImg().setVisibility(View.GONE);
        getRecordMusicPannel().setVisibility(View.GONE);
        getSoundEffectPannel().setVisibility(View.GONE);

        getRecordBottomLayout().setVisibility(View.VISIBLE);
        getRecordRightLayout().setVisibility(View.VISIBLE);
        TXUGCRecord record = VideoRecordSDK.getInstance().getRecorder();
        if (record != null) {
            record.setFocusPosition(x, y);
        }
        // 停止音乐试听，否者开启录制会录制到一点点杂音
        RecordMusicManager.getInstance().stopPreviewMusic();
    }

    @Override
    public void onRecordProgress(long milliSecond) {
        getRecordBottomLayout().updateProgress(milliSecond);

        float second = milliSecond / 1000f;
        boolean enable = second >= UGCKitRecordConfig.getInstance().mMinDuration / 1000;
        getTitleBar().setVisible(enable, ITitleBarLayout.POSITION.RIGHT);
    }

    @Override
    public void onRecordEvent(int event) {
        getRecordBottomLayout().getRecordProgressView().clipComplete();
        if (event == TXRecordCommon.EVT_ID_PAUSE) {
            Log.d(TAG, "onRecordEvent: event=EVT_ID_PAUSE");
            //相当于点击了暂停按钮
            getRecordBottomLayout().getRecordButton().pauseRecordAnim();
        }
    }

    @Override
    public void onRecordComplete(@NonNull TXRecordCommon.TXRecordResult result) {
        LogReport.getInstance().uploadLogs(LogReport.ELK_ACTION_VIDEO_RECORD, result.retCode, result.descMsg);

        if (result.retCode >= 0) {
            mProgressDialogUtil.dismissProgressDialog();
            boolean editFlag = UGCKitRecordConfig.getInstance().mIsNeedEdit;
            if (editFlag) {
                // 录制后需要进行编辑，预处理产生视频缩略图
                startPreprocess(result.videoPath);
            } else {
                // 录制后不需要进行编辑视频，直接输出录制视频路径
                if (mOnRecordListener != null) {
                    UGCKitResult ugcKitResult = new UGCKitResult();
                    String outputPath = VideoRecordSDK.getInstance().getRecordVideoPath();
                    ugcKitResult.errorCode = result.retCode;
                    ugcKitResult.descMsg = result.descMsg;
                    ugcKitResult.outputPath = outputPath;
                    ugcKitResult.coverPath = result.coverPath;
                    mOnRecordListener.onRecordCompleted(ugcKitResult);
                }
            }
        }
    }

    private void startPreprocess(String videoPath) {
        mProgressFragmentUtil = new ProgressFragmentUtil(mActivity);
        mProgressFragmentUtil.showLoadingProgress(new ProgressFragmentUtil.IProgressListener() {
            @Override
            public void onStop() {
                mProgressFragmentUtil.dismissLoadingProgress();

                ProcessKit.getInstance().stopProcess();
            }
        });

        loadVideoInfo(videoPath);
    }

    /**
     * 加载视频信息
     *
     * @param videoPath
     */
    private void loadVideoInfo(final String videoPath) {
        if(null == videoProcessExecutor) {
            videoProcessExecutor = Executors.newSingleThreadExecutor();
        }
        //使用单线程池，loadVideoInfo线程处理按照FIFO顺序执行，避免多并发产生的潜在问题
        videoProcessExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final TXVideoEditConstants.TXVideoInfo info = TXVideoInfoReader.getInstance(UGCKit.getAppContext()).getVideoFileInfo(videoPath);
                        BackgroundTasks.getInstance().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                processVideo(info,videoPath);
                            }
                        });
                    }
                });
    }

    private void processVideo(TXVideoEditConstants.TXVideoInfo info,final String videoPath) {
        if (info == null) {
            DialogUtil.showDialog(getContext(), getResources().getString(R.string.ugckit_video_preprocess_activity_edit_failed), getResources().getString(R.string.ugckit_does_not_support_android_version_below_4_3), null);
        } else {
            // 设置视频基本信息
            VideoEditerSDK.getInstance().initSDK();
            VideoEditerSDK.getInstance().setVideoPath(videoPath);
            VideoEditerSDK.getInstance().setTXVideoInfo(info);
            VideoEditerSDK.getInstance().setVideoDuration(info.duration);
            VideoEditerSDK.getInstance().setCutterStartTime(0, info.duration);

            ProcessKit.getInstance().setOnUpdateUIListener(new OnUpdateUIListener() {
                @Override
                public void onUIProgress(float progress) {
                    mProgressFragmentUtil.updateGenerateProgress((int) (progress * 100));
                }

                @Override
                public void onUIComplete(int retCode, String descMsg) {
                    // 更新UI控件
                    mProgressFragmentUtil.dismissLoadingProgress();
                    if (mOnRecordListener != null) {
                        UGCKitResult ugcKitResult = new UGCKitResult();
                        ugcKitResult.errorCode = retCode;
                        ugcKitResult.descMsg = descMsg;
                        ugcKitResult.outputPath = videoPath;
                        mOnRecordListener.onRecordCompleted(ugcKitResult);
                    }
                }

                @Override
                public void onUICancel() {
                    // 更新Activity
                    if (mOnRecordListener != null) {
                        mOnRecordListener.onRecordCanceled();
                    }
                }
            });
            // 开始视频预处理
            ProcessKit.getInstance().startProcess();
        }
    }

    @Override
    public void setConfig(UGCKitRecordConfig config) {
        VideoRecordSDK.getInstance().setConfig(config);
        // 初始化最大/最小视频录制时长
        getRecordBottomLayout().initDuration();
        // 设置默认的录制模式
        getRecordBottomLayout().getRecordButton().setCurrentRecordMode(UGCKitRecordConfig.getInstance().mRecordMode);
        // 设置视频比例UI
        getRecordRightLayout().setAspect(config.mAspectRatio);
    }

    @Override
    public void setEditVideoFlag(boolean enable) {
        UGCKitRecordConfig.getInstance().mIsNeedEdit = enable;
    }



    private void loadXmagicRes() {
        if (XMagicImpl.isLoadedRes) {
            XmagicResParser.parseRes(mActivity.getApplicationContext());
            initXMagic();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                XmagicResParser.copyRes(mActivity.getApplicationContext());
                XmagicResParser.parseRes(mActivity.getApplicationContext());
                XMagicImpl.isLoadedRes = true;
                initXMagic();
            }
        }).start();
    }


    /**
     * 初始化美颜SDK
     */
    private void initXMagic() {
        // 此处做延迟是因为在stop方法中会触发registerVideoProcessListener方法中设置的onTextureDestroyed方法执行，
        // 但是onTextureDestroyed方法是在GL线程执行，并且执行的时机可能比较晚。
        // 场景复现：快速的切换切后台，会导致start()方法已经执行，但onTextureDestroyed后执行，这样就会导致新创建的xmagic对象被销毁
       BackgroundTasks.getInstance().postDelayed(new Runnable() {
           @Override
           public void run() {
               if (mXMagic == null) {
                   mXMagic = new XMagicImpl(mActivity, getTEPanel());
                   if (mHostFragment != null) {
                       mXMagic.setHostFragment(mHostFragment);
                   }
               } else {
                   mXMagic.onResume();
               }
               mXmagicState = XMagicImpl.XmagicState.STARTED;
           }
       },500);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mXMagic != null && data != null) {
            mXMagic.onActivityResult(requestCode, resultCode, data);
        }
    }


    private void registerVideoProcessListener() {
        TXUGCRecord instance = TXUGCRecord.getInstance(UGCKit.getAppContext());
        instance.setVideoProcessListener(new TXUGCRecord.VideoCustomProcessListener() {
            @Override
            public int onTextureCustomProcess(int textureId, int width, int height) {
                if (mBeautyType == 1 && mXmagicState == XMagicImpl.XmagicState.STARTED && mXMagic != null) {
                    return mXMagic.process(textureId, width, height);
                }
                return textureId;
            }

            @Override
            public void onDetectFacePoints(float[] floats) {
            }

            @Override
            public void onTextureDestroyed() {
                if (Looper.getMainLooper() != Looper.myLooper()) {  //非主线程
                    if (mXMagic != null) {
                        mXMagic.onDestroy();
                    }
                    Log.e(TAG, "XMagicImpl.XmagicState = " + mXmagicState.name());
                    mIsTextureDestroyed = true;
                    unRegisterVideoProcessListener();
                }
            }
        });
    }


    private synchronized void unRegisterVideoProcessListener() {
        BackgroundTasks.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mIsTextureDestroyed && mIsReleased) {
                    Log.e(TAG, "setVideoProcessListener(null)");
                    TXUGCRecord.getInstance(UGCKit.getAppContext()).setVideoProcessListener(null);
                }
            }
        });
    }


    public void setHostFragment(Fragment mHostFragment) {
        this.mHostFragment = mHostFragment;
    }
}
