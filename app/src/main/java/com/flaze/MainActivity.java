package com.flaze;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.billthefarmer.mididriver.MidiDriver;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity implements BillingManager.BillingListener, PianoKeyboardView.NoteListener {
    private static final int[] FREE = {0, 10, 24, 40, 56, 73};
    private static final int[] PRO = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,19,24,25,26,27,40,41,42,56,73};
    private static final int EXPORT_REQUEST = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler playback = new Handler(Looper.getMainLooper());
    private final Handler preview = new Handler(Looper.getMainLooper());
    private final MidiRecording take = new MidiRecording();
    private MidiDriver midi;
    private BillingManager billing;
    private SharedPreferences prefs;
    private PianoKeyboardView keyboard;
    private MaterialButton recordButton, playButton, exportButton, metroButton;
    private TextView sessionStatus;
    private BottomSheetDialog premiumSheet, controlsSheet;
    private View premiumContent, controlsContent;
    private ConsentInformation consent;
    private AdView banner;
    private ToneGenerator tone;
    private int program, transpose, velocity = 100, bpm = 100;
    private boolean sustain, recording, playing, metronome, audioReady, destroyed, adsStarting, resumed;
    private long recordStart, beatAt;
    private int beat;
    private int adGeneration;
    private int previewProgram = 4;
    private boolean previewing;
    private byte[] pendingExport;

    @Override
    protected void attachBaseContext(Context context) {
        int mode = context.getSharedPreferences("midi_piano_prefs", MODE_PRIVATE).getInt("appearance", 0);
        int nightMode = (mode == 1) ? AppCompatDelegate.MODE_NIGHT_NO :
                (mode == 2) ? AppCompatDelegate.MODE_NIGHT_YES :
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(nightMode);
        super.attachBaseContext(context);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(light);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(light);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.studioRoot);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(pad + bars.left, bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        prefs = getSharedPreferences("midi_piano_prefs", MODE_PRIVATE);
        program = prefs.getInt("instrument", 0);
        transpose = Math.max(-24, Math.min(24, prefs.getInt("transpose", 0)));
        velocity = Math.max(10, Math.min(127, prefs.getInt("velocity", 100)));
        bpm = Math.max(40, Math.min(240, prefs.getInt("bpm", 100)));
        sustain = prefs.getBoolean("sustain", false);
        keyboard = findViewById(R.id.pianoKeyboard);
        keyboard.setNoteListener(this);
        keyboard.setLabels(prefs.getBoolean("labels", true));
        keyboard.setBase(48 + transpose);
        recordButton = findViewById(R.id.recordButton);
        playButton = findViewById(R.id.playButton);
        exportButton = findViewById(R.id.exportButton);
        metroButton = findViewById(R.id.metronomeButton);
        sessionStatus = findViewById(R.id.sessionStatus);
        midi = MidiDriver.getInstance();
        midi.setOnMidiStartListener(() -> main.post(() -> {
            if (destroyed || !resumed) return;
            audioReady = true;
            send(0xc0, program, 0);
            send(0xb0, 64, sustain ? 127 : 0);
        }));
        setupSheets();
        billing = new BillingManager(this, this);
        updateProUI();

        findViewById(R.id.controlsButton).setOnClickListener(v -> { refreshControls(); controlsSheet.show(); });
        findViewById(R.id.octaveDown).setOnClickListener(v -> setTranspose(transpose - 12));
        findViewById(R.id.octaveUp).setOnClickListener(v -> setTranspose(transpose + 12));
        recordButton.setOnClickListener(v -> {
            if (recording) finishRecording();
            else if (take.hasNotes()) new MaterialAlertDialogBuilder(this).setTitle(R.string.new_take_title)
                    .setMessage(R.string.new_take_message).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.record, (dialog, which) -> startRecording()).show();
            else startRecording();
        });
        playButton.setOnClickListener(v -> { if (playing) stopPlayback(); else replay(); });
        exportButton.setOnClickListener(v -> exportTake());
        metroButton.setOnClickListener(v -> { if (metronome) stopMetronome(); else startMetronome(); });
        updateOctave(); loadTake(); updateSession(); initConsent();
        if (state != null) pendingExport = state.getByteArray("pending_export");
    }

    private void setupSheets() {
        controlsSheet = new BottomSheetDialog(this);
        controlsContent = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_controls, (ViewGroup) getWindow().getDecorView(), false);
        controlsSheet.setContentView(controlsContent);
        MaterialButton appearance = controlsContent.findViewById(R.id.appearanceButton);
        String[] appearances = getResources().getStringArray(R.array.appearance_options);
        appearance.setText(getString(R.string.appearance_value, appearances[prefs.getInt("appearance", 0)]));
        appearance.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.appearance).setSingleChoiceItems(appearances, prefs.getInt("appearance", 0), (dialog, which) -> {
                    dialog.dismiss();
                    if (which != prefs.getInt("appearance", 0)) {
                        finishRecording();
                        stopPlayback();
                        stopMetronome();
                        silence();
                        prefs.edit().putInt("appearance", which).apply();
                        int nightMode = (which == 1) ? AppCompatDelegate.MODE_NIGHT_NO :
                                (which == 2) ? AppCompatDelegate.MODE_NIGHT_YES :
                                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                        AppCompatDelegate.setDefaultNightMode(nightMode);
                        recreate();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show());
        controlsContent.findViewById(R.id.transposeDown).setOnClickListener(v -> setTranspose(transpose - 12));
        controlsContent.findViewById(R.id.transposeUp).setOnClickListener(v -> setTranspose(transpose + 12));
        MaterialSwitch pedal = controlsContent.findViewById(R.id.sustainSwitch);
        pedal.setChecked(sustain);
        pedal.setOnCheckedChangeListener((v, checked) -> { sustain = checked; send(0xb0,64,checked ? 127 : 0); saveSettings(); });
        Slider dynamics = controlsContent.findViewById(R.id.velocitySlider);
        dynamics.setValue(velocity);
        dynamics.addOnChangeListener((slider, value, user) -> {
            if (user && billing != null && billing.isPro()) { velocity = (int) value; saveSettings(); refreshControls(); }
        });
        MaterialSwitch labels = controlsContent.findViewById(R.id.labelsSwitch);
        labels.setChecked(prefs.getBoolean("labels", true));
        labels.setOnCheckedChangeListener((v, checked) -> { keyboard.setLabels(checked); prefs.edit().putBoolean("labels",checked).apply(); });
        Slider tempo = controlsContent.findViewById(R.id.tempoSlider);
        tempo.setValue(bpm);
        tempo.addOnChangeListener((slider, value, user) -> {
            bpm = (int) value;
            ((TextView) controlsContent.findViewById(R.id.tempoValue)).setText(getString(R.string.tempo_value, bpm));
            if (user) { saveSettings(); if (metronome) { stopMetronome(); startMetronome(); } }
        });
        controlsContent.findViewById(R.id.privacyButton).setOnClickListener(v -> UserMessagingPlatform.showPrivacyOptionsForm(this, error -> {
            updatePrivacyButton(); if (error != null) toast(error.getMessage());
            resetBanner(); loadBanner();
        }));
        controlsContent.findViewById(R.id.velocityProHint).setOnClickListener(v -> { controlsSheet.dismiss(); showPremium(); });
        premiumSheet = new BottomSheetDialog(this);
        premiumContent = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_premium, (ViewGroup) getWindow().getDecorView(), false);
        premiumSheet.setContentView(premiumContent);
        premiumSheet.setOnShowListener(dialog -> {
            View sheet = premiumSheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) BottomSheetBehavior.from(sheet).setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        premiumSheet.setOnDismissListener(dialog -> stopPreview());
        MaterialAutoCompleteTextView sound = premiumContent.findViewById(R.id.previewSelector);
        String[] names = getResources().getStringArray(R.array.instrument_array_pro);
        sound.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown_sound, names));
        sound.setText(names[4],false);
        sound.setOnItemClickListener((parent,view,pos,id) -> { stopPreview(); previewProgram = PRO[pos]; });
        premiumContent.findViewById(R.id.previewButton).setOnClickListener(v -> previewSound());
        premiumContent.findViewById(R.id.purchaseButton).setOnClickListener(v -> {
            if (billing.canPurchase()) billing.launchPurchaseFlow(); else billing.refresh();
        });
        premiumContent.findViewById(R.id.restoreButton).setOnClickListener(v -> billing.restorePurchases());
    }
    private void showPremium() {
        finishRecording(); stopPlayback(); silence(); billing.refresh(); refreshPremium(); premiumSheet.show();
    }
    private void previewSound() {
        if (!audioReady) { toast(getString(R.string.audio_loading)); return; }
        stopPreview(); silence(); previewing = true;
        send(0xb0,64,0); send(0xc0,previewProgram,0);
        int[] notes = {60,64,67,72};
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < notes.length; i++) {
            int note = notes[i];
            preview.postAtTime(() -> send(0x90,note,100),start + i * 180);
            preview.postAtTime(() -> send(0x80,note,0),start + i * 180 + 500);
        }
        preview.postAtTime(this::stopPreview,start + 1200);
    }
    private void stopPreview() {
        preview.removeCallbacksAndMessages(null);
        if (previewing) { previewing = false; silence(); send(0xc0,program,0); }
    }

    private void refreshControls() {
        boolean pro = billing != null && billing.isPro();
        Slider slider = controlsContent.findViewById(R.id.velocitySlider);
        slider.setEnabled(pro); slider.setAlpha(pro ? 1f : .4f);
        ((TextView) controlsContent.findViewById(R.id.velocityValue)).setText(getString(R.string.velocity_value, Math.round((pro ? velocity : 127) * 100f / 127)));
        controlsContent.findViewById(R.id.velocityProHint).setVisibility(pro ? View.GONE : View.VISIBLE);
        ((TextView) controlsContent.findViewById(R.id.tempoValue)).setText(getString(R.string.tempo_value, bpm));
        updatePrivacyButton();
    }
    private void refreshPremium() {
        if (billing == null || premiumContent == null) return;
        ((TextView) premiumContent.findViewById(R.id.priceText)).setText(billing.getProPrice());
        ((TextView) premiumContent.findViewById(R.id.billingStatus)).setText(billing.getStatus());
        MaterialButton buy = premiumContent.findViewById(R.id.purchaseButton);
        buy.setEnabled(!billing.isBusy() && !billing.isRestoring() && !billing.isPro());
        buy.setText(billing.canPurchase() ? getString(R.string.buy_price, billing.getProPrice())
                : getString(billing.isBusy() ? R.string.loading_price : R.string.retry));
        MaterialButton restore = premiumContent.findViewById(R.id.restoreButton);
        restore.setEnabled(!billing.isRestoring());
        restore.setText(billing.isRestoring() ? R.string.restoring : R.string.restore_purchase);
    }
    private void updateProUI() {
        if (billing == null) return;
        boolean pro = billing.isPro();
        MaterialButton button = findViewById(R.id.premiumButton);
        button.setText(pro ? R.string.premium_badge : R.string.premium);
        button.setEnabled(true);
        button.setOnClickListener(v -> {
            if (billing.isPro()) toast(getString(R.string.pro_active));
            else showPremium();
        });
        if (pro) {
            resetBanner();
        } else loadBanner();
        updateInstruments(); refreshControls(); refreshPremium();
    }
    private void updateInstruments() {
        if (billing == null) return;
        int[] codes = billing.isPro() ? PRO : FREE;
        String[] names = getResources().getStringArray(billing.isPro() ? R.array.instrument_array_pro : R.array.instrument_array);
        int selected = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i] == program) selected = i;
        if (program != codes[selected]) { silence(); program = codes[selected]; send(0xc0,program,0); }
        MaterialAutoCompleteTextView selector = findViewById(R.id.selector);
        selector.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown_sound, names));
        selector.setText(names[selected], false);
        selector.setOnItemClickListener((parent, view, pos, id) -> { silence(); program = codes[pos]; send(0xc0,program,0); saveSettings(); });
    }
    private void setTranspose(int value) {
        silence(); transpose = Math.max(-24,Math.min(24,value)); keyboard.setBase(48 + transpose); updateOctave(); saveSettings();
    }
    private void updateOctave() {
        ((TextView) findViewById(R.id.octaveText)).setText(getString(R.string.current_octave, 3 + transpose / 12));
        findViewById(R.id.octaveDown).setEnabled(transpose > -24); findViewById(R.id.octaveUp).setEnabled(transpose < 24);
    }
    private void saveSettings() {
        prefs.edit().putInt("instrument",program).putInt("transpose",transpose).putInt("velocity",velocity)
                .putInt("bpm",bpm).putBoolean("sustain",sustain).apply();
    }
    private void send(int status, int data1, int data2) {
        if (!audioReady) return;
        midi.write(status == 0xc0 ? new byte[]{(byte)status,(byte)data1} : new byte[]{(byte)status,(byte)data1,(byte)data2});
        if (recording && !take.add(SystemClock.uptimeMillis() - recordStart,status,data1,data2)) finishRecording();
    }
    @Override public void onNoteOn(int note) { if (playing) stopPlayback(); send(0x90,note,billing != null && billing.isPro() ? velocity : 127); }
    @Override public void onNoteOff(int note) { send(0x80,note,0); }
    private void silence() {
        keyboard.releaseAll(); send(0xb0,64,0); send(0xb0,123,0); send(0xb0,120,0); send(0xb0,64,sustain ? 127 : 0);
    }
    private void startRecording() {
        if (!audioReady) { toast(getString(R.string.audio_loading)); return; }
        stopPlayback(); silence(); take.clear(); recordStart = SystemClock.uptimeMillis(); recording = true;
        send(0xc0,program,0); send(0xb0,64,sustain ? 127 : 0); main.post(ticker); updateSession();
    }
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            if (SystemClock.uptimeMillis() - recordStart >= MidiRecording.MAX_DURATION_MS) { finishRecording(); toast(getString(R.string.take_limit)); }
            else { updateSession(); main.postDelayed(this, 250); }
        }
    };
    private void finishRecording() {
        if (!recording) return;
        recording = false;
        long end = Math.min(MidiRecording.MAX_DURATION_MS, SystemClock.uptimeMillis() - recordStart);
        take.add(end,0xb0,64,0); take.add(end,0xb0,123,0);
        silence(); main.removeCallbacks(ticker); persistTake(); updateSession();
    }
    private void persistTake() {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(new File(getFilesDir(),"last-take.tmp")))) {
            List<MidiRecording.Event> events = take.snapshot(); out.writeInt(events.size());
            for (MidiRecording.Event e : events) { out.writeLong(e.timeMs); out.writeInt(e.status); out.writeInt(e.data1); out.writeInt(e.data2); }
        } catch (IOException e) { toast(getString(R.string.save_failed)); return; }
        if (!new File(getFilesDir(),"last-take.tmp").renameTo(new File(getFilesDir(),"last-take.dat"))) toast(getString(R.string.save_failed));
    }
    private void loadTake() {
        File file = new File(getFilesDir(),"last-take.dat"); if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int size = in.readInt(); if (size < 0 || size > MidiRecording.MAX_EVENTS) throw new IOException();
            for (int i = 0; i < size; i++) {
                long time = in.readLong(); int status = in.readInt(), d1 = in.readInt(), d2 = in.readInt();
                if (time < 0 || time > MidiRecording.MAX_DURATION_MS || (status != 0x90 && status != 0x80 && status != 0xb0 && status != 0xc0)
                        || d1 < 0 || d1 > 127 || d2 < 0 || d2 > 127) throw new IOException();
                take.add(time,status,d1,d2);
            }
        } catch (IOException e) { take.clear(); }
    }
    private void replay() {
        if (!audioReady || !take.hasNotes()) return;
        silence(); playing = true; keyboard.setEnabled(false);
        List<MidiRecording.Event> events = take.snapshot(); long start = SystemClock.uptimeMillis();
        for (MidiRecording.Event e : events) playback.postAtTime(() -> { if (playing) send(e.status,e.data1,e.data2); }, start + e.timeMs);
        playback.postAtTime(this::stopPlayback, start + events.get(events.size()-1).timeMs + 100); updateSession();
    }
    private void stopPlayback() {
        playback.removeCallbacksAndMessages(null);
        if (playing) { playing = false; silence(); send(0xc0,program,0); }
        keyboard.setEnabled(true); updateSession();
    }
    private void updateSession() {
        if (recordButton == null) return;
        recordButton.setText(recording ? R.string.stop : R.string.record); playButton.setText(playing ? R.string.stop : R.string.replay);
        playButton.setEnabled(!recording && take.hasNotes()); exportButton.setEnabled(!recording && !playing && take.hasNotes());
        findViewById(R.id.selector).setEnabled(!playing);
        sessionStatus.setText(recording ? getString(R.string.recording_time, (SystemClock.uptimeMillis()-recordStart)/1000)
                : getString(playing ? R.string.playing_take : take.hasNotes() ? R.string.take_ready : R.string.studio_hint));
    }
    private void exportTake() {
        try {
            pendingExport = take.toMidi();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("audio/midi").putExtra(Intent.EXTRA_TITLE,"Piano-" + System.currentTimeMillis() + ".mid");
            startActivityForResult(intent,EXPORT_REQUEST);
        } catch (IOException | android.content.ActivityNotFoundException e) { toast(getString(R.string.export_failed)); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data); if (request != EXPORT_REQUEST) return;
        if (result == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IOException(); out.write(pendingExport); toast(getString(R.string.export_success));
            } catch (IOException | SecurityException e) { toast(getString(R.string.export_failed)); }
        }
        pendingExport = null;
    }
    private void startMetronome() {
        try { if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 45); }
        catch (RuntimeException e) { toast(getString(R.string.audio_loading)); return; }
        metronome = true;
        beat = 0;
        beatAt = SystemClock.uptimeMillis();
        main.post(tick);
        metroButton.setContentDescription(getString(R.string.metronome_on, bpm));
        metroButton.setChecked(true);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!metronome || tone == null) return;
            tone.startTone(beat++ % 4 == 0 ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, 40);
            beatAt += 60000 / bpm; if (beatAt < SystemClock.uptimeMillis()) beatAt = SystemClock.uptimeMillis(); main.postAtTime(this,beatAt);
        }
    };

    private void stopMetronome() {
        metronome = false;
        main.removeCallbacks(tick);
        if (tone != null) tone.stopTone();
        metroButton.setContentDescription(getString(R.string.metronome));
        metroButton.setChecked(false);
    }
    private void initConsent() {
        consent = UserMessagingPlatform.getConsentInformation(this);
        consent.requestConsentInfoUpdate(this,new ConsentRequestParameters.Builder().build(),
                () -> { if (destroyed || isFinishing()) return; updatePrivacyButton(); UserMessagingPlatform.loadAndShowConsentFormIfRequired(this,error -> { updatePrivacyButton(); loadBanner(); }); },
                error -> { updatePrivacyButton(); loadBanner(); });
        loadBanner();
    }
    private void updatePrivacyButton() {
        if (controlsContent != null) controlsContent.findViewById(R.id.privacyButton).setVisibility(consent != null
                && consent.getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED ? View.VISIBLE : View.GONE);
    }
    private void loadBanner() {
        if (destroyed || consent == null || !consent.canRequestAds() || billing == null || billing.isPro() || adsStarting || banner != null) return;
        adsStarting = true;
        int generation = adGeneration;
        MobileAds.initialize(this, status -> main.post(() -> {
            if (generation != adGeneration) return;
            if (destroyed || billing.isPro() || !consent.canRequestAds()) { adsStarting = false; return; }
            FrameLayout container = findViewById(R.id.ad_container);
            container.post(() -> {
                if (generation != adGeneration) return;
                if (destroyed || billing.isPro() || !consent.canRequestAds()) { adsStarting = false; return; }
                int width = Math.max(1,(int)(container.getWidth() / getResources().getDisplayMetrics().density));
                if (width <= 1) width = Math.max(1,getResources().getConfiguration().screenWidthDp - 24);
                banner = new AdView(this);
                banner.setAdUnitId(getString(BuildConfig.DEBUG ? R.string.admob_banner_ad_unit_id_test : R.string.admob_banner_ad_unit_id));
                banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this,width));
                banner.setAdListener(new AdListener() {
                    @Override public void onAdLoaded() { if (generation == adGeneration && !destroyed && !billing.isPro()) container.setVisibility(View.VISIBLE); }
                    @Override public void onAdFailedToLoad(@NonNull LoadAdError error) { if (generation == adGeneration) container.setVisibility(View.GONE); }
                });
                container.removeAllViews(); container.addView(banner); banner.loadAd(new AdRequest.Builder().build()); adsStarting = false;
            });
        }));
    }
    private void resetBanner() {
        adGeneration++;
        if (banner != null) { banner.destroy(); banner = null; }
        adsStarting = false;
        FrameLayout container = findViewById(R.id.ad_container);
        container.removeAllViews(); container.setVisibility(View.GONE);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; midi.start(); if (billing != null) billing.refresh(); if (banner != null) banner.resume(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (pendingExport != null) state.putByteArray("pending_export", pendingExport);
    }
    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        resetBanner(); loadBanner();
    }
    @Override protected void onPause() {
        finishRecording(); stopPreview(); stopPlayback(); stopMetronome(); silence(); resumed = false; audioReady = false;
        midi.stop(); if (banner != null) banner.pause(); saveSettings(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); playback.removeCallbacksAndMessages(null); preview.removeCallbacksAndMessages(null);
        if (banner != null) banner.destroy(); if (tone != null) tone.release();
        if (billing != null) billing.destroy(); controlsSheet.dismiss(); premiumSheet.dismiss(); super.onDestroy();
    }
    @Override public void onPurchaseSuccess() { toast(getString(R.string.purchase_success)); premiumSheet.dismiss(); }
    @Override public void onPurchaseRestored() { toast(getString(R.string.purchase_restored)); premiumSheet.dismiss(); }
    @Override public void onPurchasePending() { toast(getString(R.string.purchase_pending)); }
    @Override public void onPurchaseError(String message) { toast(message); }
    @Override public void onProStatusChanged(boolean pro) { updateProUI(); }
    @Override public void onBillingStateChanged() { refreshPremium(); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
