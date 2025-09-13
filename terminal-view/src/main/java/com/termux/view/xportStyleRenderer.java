package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.view.font.FontManager;

/**
 * xport Terminal Custom Style Renderer
 * 
 * Phase 2 implementation of intelligent, context-aware terminal styling
 * Uses composition with TerminalRenderer and Geist font integration
 */
public final class xportStyleRenderer {
    
    private final TerminalRenderer mBaseRenderer;
    private final Typeface mGeistMono;
    private final Typeface mGeistMonoBold;
    private final Typeface mGeistMonoItalic;
    private final Typeface mInter;
    private final Typeface mInterBold;
    private final Typeface mInterItalic;
    private final Typeface mInterThin;
    private final Paint mAnalysisPaint;
    
    // Color theme properties
    private int mTextColor;
    private int mBackgroundColor;
    
    // Context analysis state
    private boolean mInSSHSession = false;
    private String mCurrentProgram = "";
    private boolean mAnalysisEnabled = true;
    
    
    public xportStyleRenderer(Context context, int textSize) {
        // Load Geist font variants from assets
        mGeistMono = loadGeistMono(context);
        mGeistMonoBold = loadGeistMonoBold(context);
        mGeistMonoItalic = loadGeistMonoItalic(context);
        
        // Load Inter font variants from assets
        mInter = loadInter(context);
        mInterBold = loadInterBold(context);
        mInterItalic = loadInterItalic(context);
        mInterThin = loadInterThin(context);
        
        // Load current color theme
        loadColorTheme();
        
        // Create base renderer with current selected font
        Typeface currentFont = getCurrentFont(context);
        mBaseRenderer = new TerminalRenderer(textSize, currentFont);
        
        // Initialize fields from base renderer
        mTextSize = mBaseRenderer.mTextSize;
        mTypeface = mBaseRenderer.mTypeface;
        mFontWidth = mBaseRenderer.mFontWidth;
        mFontLineSpacing = mBaseRenderer.mFontLineSpacing;
        mFontLineSpacingAndAscent = mBaseRenderer.mFontLineSpacingAndAscent;
        
        // Initialize analysis paint for future text analysis
        mAnalysisPaint = new Paint();
        mAnalysisPaint.setAntiAlias(true);
    }
    
    /**
     * Get current font based on FontManager selection
     */
    private Typeface getCurrentFont(Context context) {
        String currentFontName = FontManager.getFont();
        switch (currentFontName) {
            case FontManager.GEIST_MONO_BOLD:
                return mGeistMonoBold;
            case FontManager.GEIST_MONO_ITALIC:
                return mGeistMonoItalic;
            case FontManager.INTER_REGULAR:
                return mInter;
            case FontManager.INTER_BOLD:
                return mInterBold;
            case FontManager.INTER_ITALIC:
                return mInterItalic;
            case FontManager.INTER_THIN:
                return mInterThin;
            case FontManager.GEIST_MONO_REGULAR:
            default:
                return mGeistMono;
        }
    }
    
    /**
     * Load Geist Mono Regular font from assets
     */
    private static Typeface loadGeistMono(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/GeistMono-Regular.ttf");
        } catch (Exception e) {
            // Fallback to system monospace if Geist fails to load
            return Typeface.MONOSPACE;
        }
    }
    
    /**
     * Load Geist Mono Bold font from assets
     */
    private static Typeface loadGeistMonoBold(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/GeistMono-Bold.ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT_BOLD;
        }
    }
    
    /**
     * Load Geist Mono Italic font from assets
     */
    private static Typeface loadGeistMonoItalic(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/GeistMono-Italic.ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }
    
    /**
     * Load Inter Regular font from assets
     */
    private static Typeface loadInter(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/Inter-Regular.ttf");
        } catch (Exception e) {
            // Fallback to system sans serif if Inter fails to load
            return Typeface.SANS_SERIF;
        }
    }
    
    /**
     * Load Inter Bold font from assets
     */
    private static Typeface loadInterBold(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/Inter-Bold.ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT_BOLD;
        }
    }
    
    /**
     * Load Inter Italic font from assets
     */
    private static Typeface loadInterItalic(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/Inter-Italic.ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    /**
     * Load Inter Thin font from assets
     */
    private static Typeface loadInterThin(Context context) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/Inter-Thin.ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    /**
     * Enhanced render method with context analysis
     * Phase 2A: Foundation - Basic styling with Geist font and custom colors
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        
        // Store original colors
        int[] originalColors = mEmulator.mColors.mCurrentColors;
        int originalForeground = originalColors[TextStyle.COLOR_INDEX_FOREGROUND];
        int originalBackground = originalColors[TextStyle.COLOR_INDEX_BACKGROUND];
        
        // Apply custom colors to the emulator's color palette
        originalColors[TextStyle.COLOR_INDEX_FOREGROUND] = mTextColor;
        originalColors[TextStyle.COLOR_INDEX_BACKGROUND] = mBackgroundColor;
        
        // Clear entire canvas with custom background color
        canvas.drawColor(mBackgroundColor);
        
        // Standard rendering using base renderer with modified colors
        mBaseRenderer.render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2);
        
        // Restore original colors after rendering
        originalColors[TextStyle.COLOR_INDEX_FOREGROUND] = originalForeground;
        originalColors[TextStyle.COLOR_INDEX_BACKGROUND] = originalBackground;
        
        // Future Phase 2B: Add text analysis and context detection here
        if (mAnalysisEnabled) {
            analyzeTerminalContent(mEmulator, topRow);
        }
        
        // Future Phase 2C: Apply intelligent styling rules here  
        // applyContextualStyling(canvas, mEmulator, topRow);
    }
    
    /**
     * Delegate methods to base renderer for compatibility
     */
    public float getFontWidth() {
        return mBaseRenderer.getFontWidth();
    }
    
    public int getFontLineSpacing() {
        return mBaseRenderer.getFontLineSpacing();
    }
    
    // Expose base renderer fields that TerminalView needs
    public final int mTextSize;
    public final Typeface mTypeface;
    public final float mFontWidth;
    public final int mFontLineSpacing;
    public final int mFontLineSpacingAndAscent;
    
    
    /**
     * Analyze terminal content for context detection
     * This identifies SSH sessions, running programs, file listings, etc.
     */
    private void analyzeTerminalContent(TerminalEmulator emulator, int topRow) {
        if (!mAnalysisEnabled) return;
        
        // Basic context analysis for future enhancements
        // TODO: Implement text analysis pipeline
        // - Detect SSH session indicators (user@hostname:, ssh prompts)
        // - Identify running programs (ls, vim, htop, etc.)
        // - Classify output types (file listings, error messages, logs)
        // - Track connection status and authentication state
    }
    
    /**
     * Future Phase 2C: Apply contextual styling based on analysis
     * This will enhance visual presentation with intelligent color schemes
     */
    @SuppressWarnings("unused")
    private void applyContextualStyling(Canvas canvas, TerminalEmulator emulator, int topRow) {
        if (!mAnalysisEnabled) return;
        
        // TODO: Implement styling rules engine
        // - SSH session highlighting
        // - Program-specific color schemes  
        // - File type color coding in ls output
        // - Error/warning message highlighting
        // - Command prompt enhancement
    }
    
    /**
     * Enable/disable intelligent analysis and styling
     */
    public void setAnalysisEnabled(boolean enabled) {
        mAnalysisEnabled = enabled;
    }
    
    /**
     * Get current analysis state
     */
    public boolean isAnalysisEnabled() {
        return mAnalysisEnabled;
    }
    
    /**
     * Get SSH session state
     */
    public boolean isInSSHSession() {
        return mInSSHSession;
    }
    
    /**
     * Get current detected program
     */
    public String getCurrentProgram() {
        return mCurrentProgram;
    }
    
    /**
     * Load color theme from ColorThemeManager
     */
    private void loadColorTheme() {
        mTextColor = ColorThemeManager.getTextColor();
        mBackgroundColor = ColorThemeManager.getBackgroundColor();
    }
    
    /**
     * Refresh color theme from storage
     * Call this after color changes to update renderer
     */
    public void refreshColorTheme() {
        loadColorTheme();
    }
    
    /**
     * Refresh font from FontManager selection
     * Call this after font changes to update renderer
     */
    public void refreshFont(Context context, int textSize) {
        Typeface newFont = getCurrentFont(context);
        // Note: TerminalRenderer is final, so we need to recreate it
        // This method signature allows the caller to handle renderer recreation
    }
    
    /**
     * Get current font variant name
     */
    public String getCurrentFontName() {
        return FontManager.getFont();
    }
    
    /**
     * Get current font display name
     */
    public String getCurrentFontDisplayName() {
        return FontManager.getCurrentFontDisplay();
    }
    
    /**
     * Get current text color
     */
    public int getTextColor() {
        return mTextColor;
    }
    
    /**
     * Get current background color
     */
    public int getBackgroundColor() {
        return mBackgroundColor;
    }
    
    /**
     * Set text color (also saves to persistent storage)
     * @param color Color as integer (ARGB format)
     * @return true if color was set successfully
     */
    public boolean setTextColor(int color) {
        if (ColorThemeManager.setTextColor(color)) {
            mTextColor = color;
            return true;
        }
        return false;
    }
    
    /**
     * Set text color from hex string (also saves to persistent storage)
     * @param colorHex Color in hex format (e.g., "#FF0000")
     * @return true if color was set successfully
     */
    public boolean setTextColor(String colorHex) {
        if (ColorThemeManager.setTextColor(colorHex)) {
            mTextColor = ColorThemeManager.getTextColor();
            return true;
        }
        return false;
    }
    
    /**
     * Set background color (also saves to persistent storage)
     * @param color Color as integer (ARGB format)
     * @return true if color was set successfully
     */
    public boolean setBackgroundColor(int color) {
        if (ColorThemeManager.setBackgroundColor(color)) {
            mBackgroundColor = color;
            return true;
        }
        return false;
    }
    
    /**
     * Set background color from hex string (also saves to persistent storage)
     * @param colorHex Color in hex format (e.g., "#000000")
     * @return true if color was set successfully
     */
    public boolean setBackgroundColor(String colorHex) {
        if (ColorThemeManager.setBackgroundColor(colorHex)) {
            mBackgroundColor = ColorThemeManager.getBackgroundColor();
            return true;
        }
        return false;
    }
    
}