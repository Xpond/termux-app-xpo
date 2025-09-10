package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;

/**
 * XPort Terminal Custom Style Renderer
 * 
 * Phase 2 implementation of intelligent, context-aware terminal styling
 * Uses composition with TerminalRenderer and Geist font integration
 */
public final class XPortStyleRenderer {
    
    private final TerminalRenderer mBaseRenderer;
    private final Typeface mGeistMono;
    private final Typeface mGeistMonoBold;
    private final Typeface mGeistMonoItalic;
    private final Paint mAnalysisPaint;
    
    // Context analysis state
    private boolean mInSSHSession = false;
    private String mCurrentProgram = "";
    private boolean mAnalysisEnabled = true;
    
    public XPortStyleRenderer(Context context, int textSize) {
        // Load Geist font variants from assets
        mGeistMono = loadGeistMono(context);
        mGeistMonoBold = loadGeistMonoBold(context);
        mGeistMonoItalic = loadGeistMonoItalic(context);
        
        // Create base renderer with Geist font
        mBaseRenderer = new TerminalRenderer(textSize, mGeistMono);
        
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
     * Enhanced render method with context analysis
     * Phase 2A: Foundation - Basic styling with Geist font
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        
        // Phase 2A: Basic rendering with Geist font (current implementation)
        mBaseRenderer.render(mEmulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2);
        
        // Future Phase 2B: Add text analysis and context detection here
        // analyzeTerminalContent(mEmulator, topRow);
        
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
     * Future Phase 2B: Analyze terminal content for context detection
     * This will identify SSH sessions, running programs, file listings, etc.
     */
    @SuppressWarnings("unused")
    private void analyzeTerminalContent(TerminalEmulator emulator, int topRow) {
        if (!mAnalysisEnabled) return;
        
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
}