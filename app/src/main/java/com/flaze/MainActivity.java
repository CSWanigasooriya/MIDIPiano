package com.flaze;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import org.billthefarmer.mididriver.MidiDriver;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity implements MidiDriver.OnMidiStartListener, View.OnTouchListener, BillingManager.BillingListener {

    private static final String TAG_ADMOB = "AdMob";
    private static final String PREF_LANDSCAPE_HINT_SHOWN = "landscape_hint_shown";
    // Pro instruments (MIDI program numbers, 0-based)
    private static final int[] FREE_INSTRUMENTS = {0, 10, 24, 40, 56, 73}; // 6 instruments
    private static final int[] PRO_INSTRUMENTS = {0, 1, 2, 3, 4, 5, 6, 7,     // Pianos
            8, 9, 10, 11, 12, 13,       // Chromatic Percussion
            19,                          // Church Organ
            24, 25, 26, 27,             // Guitars
            40, 41, 42,                  // Strings
            56, 73                       // Brass & Woodwind
    };
    private final Set<Integer> sustainedNotes = new HashSet<>();
    private MidiDriver midiDriver;
    private byte[] event;
    private int[] instruments;
    private HorizontalScrollView scroller;
    private AdView adView;
    private boolean bannerInitialized = false;
    private int transposeSemitones = 0; // -24..+24
    private boolean sustainEnabled = false;
    private int currentVelocity = 127; // Default max velocity (0-127)
    private BottomSheetDialog controlsSheet;
    private BottomSheetDialog premiumSheet;
    // Billing & Premium
    private BillingManager billingManager;
    private MaterialButton premiumButton;
    private Chip octaveChip;
    private FrameLayout adContainer;

    private static int getSemitone(String tag, int idx, char noteChar) {
        boolean sharp = (idx < tag.length() && tag.charAt(idx) == '#');

        int semitoneBase = switch (noteChar) {
            case 'c' -> 0;
            case 'd' -> 2;
            case 'e' -> 4;
            case 'f' -> 5;
            case 'g' -> 7;
            case 'a' -> 9;
            case 'b' -> 11;
            default -> throw new IllegalArgumentException("Invalid note tag: " + tag);
        };

        return semitoneBase + (sharp ? 1 : 0);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display (modern API for Android 15+)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        // Handle window insets for edge-to-edge
        setupEdgeToEdge();

        // Initialize billing manager
        billingManager = new BillingManager(this, this);

        // Setup UI references
        premiumButton = findViewById(R.id.premiumButton);
        octaveChip = findViewById(R.id.octaveChip);
        adContainer = findViewById(R.id.ad_container);

        // Setup premium button click
        if (premiumButton != null) {
            premiumButton.setOnClickListener(v -> showPremiumSheet());
        }

        // Non-disruptive ads: banner only (no app-open / interstitial on startup).
        // AdView is created programmatically inside ad_container.
        if (!billingManager.isPro()) {
            initConsentAndAds();
        }

        MaterialAutoCompleteTextView instrumentSelector = findViewById(R.id.selector);

        //MIDI Instrument Codes for Acoustic Grand Piano, Electric Grand Piano, Music Box, Acoustic Nylon Guitar, Trumpet, Flute
        instruments = new int[]{1, 10, 24, 40, 56, 73};

        scroller = findViewById(R.id.horizontalScroller);

        // Bind all piano key buttons automatically (no repetitive findViewById).
        bindPianoKeys();

        // Get the MidiDriver instance (singleton in v1.29+)
        midiDriver = MidiDriver.getInstance();
        // Set the listener.
        midiDriver.setOnMidiStartListener(this);

        // Now setup instrument dropdown (requires midiDriver)
        setupInstrumentDropdown(instrumentSelector);

        // Update UI based on pro status (after midiDriver is initialized)
        updateProUI(billingManager.isPro());

        ViewTreeObserver vto = scroller.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scroller.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                // Improve usability: enlarge the tappable area for narrow black keys.
                expandBlackKeyTouchTargets();
            }
        });


        setupControlsBottomSheet();
        setupPremiumBottomSheet();

        View controlsButton = findViewById(R.id.controlsButton);
        if (controlsButton != null) {
            controlsButton.setOnClickListener(v -> {
                if (controlsSheet != null) controlsSheet.show();
            });
        }

        // Update octave display
        updateOctaveDisplay();

        // Show landscape hint for first-time users in portrait mode
        showLandscapeHintIfNeeded();
    }

    private void showLandscapeHintIfNeeded() {
        // Only show hint in portrait mode
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            android.content.SharedPreferences prefs = getSharedPreferences("midi_piano_prefs", MODE_PRIVATE);
            boolean hintShown = prefs.getBoolean(PREF_LANDSCAPE_HINT_SHOWN, false);

            if (!hintShown) {
                // Show a Snackbar with the hint
                View rootView = findViewById(android.R.id.content);
                com.google.android.material.snackbar.Snackbar.make(rootView, R.string.landscape_hint, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).setAction("OK", v -> {
                }).show();

                // Mark hint as shown
                prefs.edit().putBoolean(PREF_LANDSCAPE_HINT_SHOWN, true).apply();
            }
        }
    }

    private void setupControlsBottomSheet() {
        controlsSheet = new BottomSheetDialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_controls, (ViewGroup) getWindow().getDecorView(), false);
        controlsSheet.setContentView(content);

        controlsSheet.setOnShowListener(dialog -> {
            View sheet = controlsSheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setFitToContents(true);
                behavior.setState(BottomSheetBehavior.STATE_HALF_EXPANDED);
                behavior.setSkipCollapsed(false);
            }
            // Update velocity UI based on pro status when sheet is shown
            updateVelocityUI(content);
        });

        MaterialButton transposeDown = content.findViewById(R.id.transposeDown);
        MaterialButton transposeUp = content.findViewById(R.id.transposeUp);
        MaterialSwitch sustainSwitch = content.findViewById(R.id.sustainSwitch);

        // Velocity controls
        com.google.android.material.slider.Slider velocitySlider = content.findViewById(R.id.velocitySlider);
        TextView velocityValue = content.findViewById(R.id.velocityValue);

        if (transposeDown != null) {
            transposeDown.setOnClickListener(v -> setTransposeSemitones(transposeSemitones - 12));
        }
        if (transposeUp != null) {
            transposeUp.setOnClickListener(v -> setTransposeSemitones(transposeSemitones + 12));
        }

        if (sustainSwitch != null) {
            sustainSwitch.setChecked(sustainEnabled);
            sustainSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sustainEnabled = isChecked;
                if (!sustainEnabled) {
                    // Release all sustained notes when sustain is turned off.
                    for (Integer note : sustainedNotes) {
                        stopNote(note);
                    }
                    sustainedNotes.clear();
                }
            });
        }

        // Setup velocity slider
        if (velocitySlider != null && velocityValue != null) {
            velocitySlider.setValue(currentVelocity);
            velocityValue.setText(getString(R.string.velocity_value, (int) (currentVelocity / 1.27f)));

            velocitySlider.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser && billingManager != null && billingManager.isPro()) {
                    currentVelocity = (int) value;
                    velocityValue.setText(getString(R.string.velocity_value, (int) (value / 1.27f)));
                } else if (fromUser && (billingManager == null || !billingManager.isPro())) {
                    // Reset to max if not pro
                    slider.setValue(127);
                    Toast.makeText(this, R.string.velocity_pro_hint, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Update velocity UI initially
        updateVelocityUI(content);
    }

    private void updateVelocityUI(View content) {
        com.google.android.material.slider.Slider velocitySlider = content.findViewById(R.id.velocitySlider);
        TextView velocityProHint = content.findViewById(R.id.velocityProHint);

        boolean isPro = billingManager != null && billingManager.isPro();

        if (velocitySlider != null) {
            velocitySlider.setEnabled(isPro);
            velocitySlider.setAlpha(isPro ? 1.0f : 0.5f);
        }
        if (velocityProHint != null) {
            velocityProHint.setVisibility(isPro ? View.GONE : View.VISIBLE);
        }
    }

    private void setTransposeSemitones(int newValue) {
        // clamp to a reasonable range
        if (newValue < -24) newValue = -24;
        if (newValue > 24) newValue = 24;
        transposeSemitones = newValue;
        updateOctaveDisplay();
    }

    private void updateOctaveDisplay() {
        if (octaveChip != null) {
            // Base octave is 3-4, transpose shifts by octaves
            int baseOctave = 3 + (transposeSemitones / 12);
            octaveChip.setText(getString(R.string.current_octave, baseOctave));
        }
    }

    private void updateProUI(boolean isPro) {
        // Hide premium button if already pro
        if (premiumButton != null) {
            premiumButton.setVisibility(isPro ? View.GONE : View.VISIBLE);
        }

        // Hide ads if pro
        if (adContainer != null) {
            adContainer.setVisibility(isPro ? View.GONE : View.VISIBLE);
        }

        // Adjust keyboard padding based on pro status
        ViewGroup keyboardRoot = findViewById(R.id.keyboardRoot);
        if (keyboardRoot != null) {
            if (isPro) {
                // No ad, no bottom padding needed
                keyboardRoot.setPadding(0, 0, 0, 0);
            } else if (adContainer != null && adContainer.getVisibility() == View.VISIBLE) {
                // Ad is visible, add padding
                int adHeight = adContainer.getHeight();
                if (adHeight <= 0) {
                    adHeight = (int) (70 * getResources().getDisplayMetrics().density);
                }
                int padding = adHeight + (int) (8 * getResources().getDisplayMetrics().density);
                keyboardRoot.setPadding(0, 0, 0, padding);
            }
        }

        // Destroy ad view if pro
        if (isPro && adView != null) {
            adView.destroy();
            adView = null;
        }

        // If switching back to non-pro and ads haven't been initialized, start them
        if (!isPro && !bannerInitialized) {
            initConsentAndAds();
        }

        // Refresh instrument list for pro users
        MaterialAutoCompleteTextView instrumentSelector = findViewById(R.id.selector);
        if (instrumentSelector != null) {
            updateInstrumentList(instrumentSelector);
        }

        // Reset velocity if switching from pro to free
        if (!isPro) {
            currentVelocity = 127;
        }
    }

    private void showPremiumSheet() {
        if (premiumSheet != null) {
            // Update price before showing
            TextView priceText = premiumSheet.findViewById(R.id.priceText);
            if (priceText != null && billingManager != null) {
                priceText.setText(billingManager.getProPrice());
            }
            premiumSheet.show();
        }
    }

    private void setupPremiumBottomSheet() {
        premiumSheet = new BottomSheetDialog(this);
        View content = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_premium, (ViewGroup) getWindow().getDecorView(), false);
        premiumSheet.setContentView(content);

        premiumSheet.setOnShowListener(dialog -> {
            View sheet = premiumSheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundResource(android.R.color.transparent);
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        MaterialButton purchaseButton = content.findViewById(R.id.purchaseButton);
        MaterialButton restoreButton = content.findViewById(R.id.restoreButton);
        TextView priceText = content.findViewById(R.id.priceText);

        if (priceText != null && billingManager != null) {
            priceText.setText(billingManager.getProPrice());
        }

        if (purchaseButton != null) {
            purchaseButton.setOnClickListener(v -> {
                if (billingManager != null) {
                    billingManager.launchPurchaseFlow();
                }
            });
        }

        if (restoreButton != null) {
            restoreButton.setOnClickListener(v -> {
                if (billingManager != null) {
                    billingManager.restorePurchases();
                }
            });
        }
    }

    // BillingManager.BillingListener callbacks
    @Override
    public void onPurchaseSuccess() {
        Toast.makeText(this, R.string.purchase_success, Toast.LENGTH_SHORT).show();
        if (premiumSheet != null && premiumSheet.isShowing()) {
            premiumSheet.dismiss();
        }
    }

    @Override
    public void onPurchaseRestored() {
        Toast.makeText(this, R.string.purchase_restored, Toast.LENGTH_SHORT).show();
        if (premiumSheet != null && premiumSheet.isShowing()) {
            premiumSheet.dismiss();
        }
    }

    @Override
    public void onPurchasePending() {
        Toast.makeText(this, R.string.purchase_pending, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onPurchaseError(String message) {
        Toast.makeText(this, getString(R.string.purchase_error) + ": " + message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProStatusChanged(boolean isPro) {
        updateProUI(isPro);
    }

    private void bindPianoKeys() {
        ViewGroup keyboardRoot = findViewById(R.id.keyboardRoot);
        if (keyboardRoot == null) {
            // Fallback: scan the whole view tree
            View root = getWindow().getDecorView().getRootView();
            if (root instanceof ViewGroup) {
                keyboardRoot = (ViewGroup) root;
            } else {
                return;
            }
        }
        bindPianoKeysRecursive(keyboardRoot);
    }

    private void bindPianoKeysRecursive(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Object tagObj = child.getTag();
            if (tagObj instanceof String tag) {
                if (looksLikeNoteTag(tag)) {
                    child.setOnTouchListener(this);
                    // Set accessibility label for screen readers
                    String noteLabel = formatNoteLabel(tag);
                    child.setContentDescription(getString(R.string.cd_piano_key, noteLabel));
                }
            }
            if (child instanceof ViewGroup) {
                bindPianoKeysRecursive((ViewGroup) child);
            }
        }
    }

    private String formatNoteLabel(String tag) {
        // Convert "c3#" to "C3 sharp", "d4" to "D4"
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(tag.charAt(0)));
        for (int i = 1; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (c == '#') {
                sb.append(" sharp");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean looksLikeNoteTag(String tag) {
        // Expected: c3, d4, f3#, etc.
        if (tag == null || tag.length() < 2) return false;
        char note = Character.toLowerCase(tag.charAt(0));
        if (note < 'a' || note > 'g') return false;
        // must contain a digit octave
        for (int i = 1; i < tag.length(); i++) {
            if (Character.isDigit(tag.charAt(i))) return true;
        }
        return false;
    }

    private int midiNoteFromTag(String tag) {
        // MIDI note number: C0=12. Your existing math was: (octave*12)+semitone where octave=(i+1).
        // This effectively means C1=24, C3=48, C4=60. We'll preserve that by mapping octaveNumber -> (octaveNumber+1)*12.

        // Parse: <note><octave>[#]
        char noteChar = Character.toLowerCase(tag.charAt(0));
        int idx = 1;
        int octave = 0;
        boolean hasDigit = false;
        while (idx < tag.length() && Character.isDigit(tag.charAt(idx))) {
            hasDigit = true;
            octave = octave * 10 + (tag.charAt(idx) - '0');
            idx++;
        }
        if (!hasDigit) throw new IllegalArgumentException("Invalid note tag: " + tag);

        int semitone = getSemitone(tag, idx, noteChar);
        int base = (octave + 1) * 12;
        return base + semitone;
    }

    private void initConsentAndAds() {
        // Request consent where required (EEA/UK). If consent isn't required, this returns quickly.
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build();

        ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(this);

        consentInformation.requestConsentInfoUpdate(this, params, () -> {
            // Load/show consent form if needed.
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(this, formError -> {
                // Even if we can't show a form, we can still attempt to load ads where allowed.
                startMobileAdsAndLoadBanner();
            });
        }, requestError -> {
            // If consent request fails, don't block app usage.
            startMobileAdsAndLoadBanner();
        });
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private String getBannerAdUnitId() {
        // Use Google's official test ID in debuggable builds to avoid "No fill" during development.
        if (isDebuggable()) {
            return getString(R.string.admob_banner_ad_unit_id_test);
        }
        return getString(R.string.admob_banner_ad_unit_id);
    }

    private void startMobileAdsAndLoadBanner() {
        if (bannerInitialized) return;
        bannerInitialized = true;

        MobileAds.initialize(this, initializationStatus -> {
            FrameLayout adContainer = findViewById(R.id.ad_container);
            if (adContainer == null) return;

            AdView bannerView = new AdView(this);
            bannerView.setAdUnitId(getBannerAdUnitId());

            // Measure width in dp for anchored adaptive banner.
            DisplayMetrics outMetrics = getResources().getDisplayMetrics();
            int adWidthDp = (int) (outMetrics.widthPixels / outMetrics.density);

            AdSize adaptiveSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp);
            bannerView.setAdSize(adaptiveSize);

            // Ensure the view gets measured/laid out.
            bannerView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

            bannerView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    Log.d(TAG_ADMOB, "Banner loaded");
                    // Adjust keyboard padding to match actual ad height
                    adjustKeyboardPaddingForAd(adContainer);
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                    Log.w(TAG_ADMOB, "Banner failed to load: " + adError);
                    // Hide ad container if ad fails to load
                    adContainer.setVisibility(View.GONE);
                    // Remove bottom padding since no ad
                    ViewGroup keyboardRoot = findViewById(R.id.keyboardRoot);
                    if (keyboardRoot != null) {
                        keyboardRoot.setPadding(0, 0, 0, 0);
                    }
                }
            });

            adContainer.removeAllViews();
            adContainer.addView(bannerView);

            AdRequest request = new AdRequest.Builder().build();
            bannerView.loadAd(request);

            // Keep a reference for lifecycle management.
            adView = bannerView;
        });
    }

    private void adjustKeyboardPaddingForAd(View adContainer) {
        // Wait for the ad container to be measured
        adContainer.post(() -> {
            int adHeight = adContainer.getHeight();
            if (adHeight <= 0) {
                // Default padding if measurement fails
                adHeight = (int) (70 * getResources().getDisplayMetrics().density);
            }

            ViewGroup keyboardRoot = findViewById(R.id.keyboardRoot);
            if (keyboardRoot != null) {
                // Add some extra padding for safety
                int padding = adHeight + (int) (8 * getResources().getDisplayMetrics().density);
                keyboardRoot.setPadding(0, 0, 0, padding);
            }

            // Update the ad container height to match (use ViewGroup.LayoutParams for compatibility)
            ViewGroup.LayoutParams params = adContainer.getLayoutParams();
            if (params != null) {
                params.height = adHeight;
                adContainer.setLayoutParams(params);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.midiDriver.start();

        if (adView != null) {
            adView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        midiDriver.stop();

        if (adView != null) {
            adView.pause();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Enter PiP mode when user presses home button (Android 8.0+)
        enterPipMode();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
        }
        if (billingManager != null) {
            billingManager.destroy();
        }
        super.onDestroy();
    }

    private void playNote(int note) {

        // Construct a note ON message for the note at the current velocity on channel 1:
        event = new byte[3];
        event[0] = (byte) (0x90);  // 0x90 = note On, 0x00 = channel 1
        event[1] = (byte) note;
        // Use velocity control if pro, otherwise max velocity
        int velocity = (billingManager != null && billingManager.isPro()) ? currentVelocity : 127;
        event[2] = (byte) velocity;

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    private void stopNote(int note) {

        // Construct a note OFF message for the middle C at minimum velocity on channel 1:
        event[0] = (byte) (0x80);  // 0x80 = note Off, 0x00 = channel 1
        event[1] = (byte) note;
        event[2] = (byte) 0x00;  // 0x00 = the minimum velocity (0)

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    private void selectInstrument(int instrument) {
        if (midiDriver == null) return;

        // Construct a program change to select the instrument on channel 1:
        event = new byte[2];
        event[0] = (byte) (0xC0); // 0xC0 = program change, 0x00 = channel 1
        event[1] = (byte) instrument;

        // Send the MIDI event to the synthesizer.
        midiDriver.write(event);

    }

    @Override
    public void onMidiStart() {
        //..
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        try {
            Object tagObj = v.getTag();
            if (!(tagObj instanceof String tag)) return false;

            int noteNumber = midiNoteFromTag(tag) + transposeSemitones;

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sustainedNotes.remove(noteNumber);
                playNote(noteNumber);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (sustainEnabled) {
                    sustainedNotes.add(noteNumber);
                } else {
                    stopNote(noteNumber);
                }
            }
        } catch (Exception e) {
            Log.w(getClass().getName(), "Touch handling failed", e);
        }
        return false;
    }

    private void expandBlackKeyTouchTargets() {
        ViewGroup root = findViewById(R.id.keyboardRoot);
        if (root == null) return;

        // Collect delegates in one composite to avoid only the last one taking effect.
        TouchDelegateComposite composite = new TouchDelegateComposite(root);

        int minTargetPx = (int) (48f * getResources().getDisplayMetrics().density);

        // Expand only sharps (black keys). This preserves white key feel.
        int[] blackKeyIds = new int[]{R.id.c3Sharp, R.id.d3Sharp, R.id.f3Sharp, R.id.g3Sharp, R.id.a3Sharp, R.id.c4Sharp, R.id.d4Sharp, R.id.f4Sharp, R.id.g4Sharp, R.id.a4Sharp};

        for (int id : blackKeyIds) {
            View key = findViewById(id);
            if (key == null) continue;

            Rect hit = new Rect();
            key.getHitRect(hit);

            int width = hit.width();
            int height = hit.height();

            int dx = Math.max(0, (minTargetPx - width) / 2);
            int dy = Math.max(0, (minTargetPx - height) / 2);

            // Expand more upwards than downwards to avoid stealing the white key lower area.
            hit.left -= dx;
            hit.right += dx;
            hit.top -= dy;
            hit.bottom += Math.max(0, dy / 3);

            composite.addDelegate(new TouchDelegate(hit, key));
        }

        root.setTouchDelegate(composite);
    }

    private void setupInstrumentDropdown(MaterialAutoCompleteTextView instrumentSelector) {
        if (instrumentSelector == null) return;

        updateInstrumentList(instrumentSelector);
    }

    private void updateInstrumentList(MaterialAutoCompleteTextView instrumentSelector) {
        if (instrumentSelector == null) return;

        boolean isPro = billingManager != null && billingManager.isPro();

        String[] names;
        if (isPro) {
            names = getResources().getStringArray(R.array.instrument_array_pro);
            instruments = PRO_INSTRUMENTS;
        } else {
            names = getResources().getStringArray(R.array.instrument_array);
            instruments = FREE_INSTRUMENTS;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        instrumentSelector.setAdapter(adapter);

        // Set a default selection (only select instrument if midiDriver is ready)
        if (names.length > 0) {
            instrumentSelector.setText(names[0], false);
            // Only send MIDI if driver is initialized and started
            if (midiDriver != null) {
                selectInstrument(instruments[0]);
            }
        }

        instrumentSelector.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < instruments.length) {
                selectInstrument(instruments[position]);
            }
        });
    }

    private void setupEdgeToEdge() {
        View rootView = findViewById(android.R.id.content);
        if (rootView == null) return;

        // Apply window insets listener for proper padding around system UI
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // Apply padding to root to prevent content from going behind system bars
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);

            // Return the insets to propagate to children
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Handle orientation changes
        if (scroller != null) {
            scroller.post(() -> scroller.scrollTo(0, 0));
        }
    }

    /**
     * Enter Picture-in-Picture mode for floating piano window
     */
    public void enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Rational aspectRatio = new Rational(16, 9);
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio);

            try {
                enterPictureInPictureMode(builder.build());
            } catch (Exception e) {
                Log.w(TAG_ADMOB, "Failed to enter PiP mode", e);
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        // Hide/show UI elements based on PiP mode
        View controlsButton = findViewById(R.id.controlsButton);
        View instrumentField = findViewById(R.id.instrumentField);

        if (isInPictureInPictureMode) {
            // Hide controls when in PiP mode - only show piano keys
            if (controlsButton != null) controlsButton.setVisibility(View.GONE);
            if (premiumButton != null) premiumButton.setVisibility(View.GONE);
            if (octaveChip != null) octaveChip.setVisibility(View.GONE);
            if (instrumentField != null) instrumentField.setVisibility(View.GONE);
            if (adContainer != null) adContainer.setVisibility(View.GONE);
        } else {
            // Restore controls when exiting PiP mode
            if (controlsButton != null) controlsButton.setVisibility(View.VISIBLE);
            if (octaveChip != null) octaveChip.setVisibility(View.VISIBLE);
            if (instrumentField != null) instrumentField.setVisibility(View.VISIBLE);

            // Only show premium button if not pro
            if (premiumButton != null && (billingManager == null || !billingManager.isPro())) {
                premiumButton.setVisibility(View.VISIBLE);
            }
            // Only show ads if not pro
            if (adContainer != null && (billingManager == null || !billingManager.isPro())) {
                adContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Android only supports 1 TouchDelegate per View.
     * This composite lets us expand multiple children at once.
     */
    private static final class TouchDelegateComposite extends TouchDelegate {
        private final java.util.List<TouchDelegate> delegates = new java.util.ArrayList<>();

        TouchDelegateComposite(View delegateView) {
            super(new Rect(), delegateView);
        }

        void addDelegate(TouchDelegate delegate) {
            delegates.add(delegate);
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            for (TouchDelegate d : delegates) {
                if (d.onTouchEvent(event)) return true;
            }
            return false;
        }
    }
}
