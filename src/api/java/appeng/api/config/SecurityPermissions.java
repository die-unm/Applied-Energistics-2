/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 AlgorithmX2
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package appeng.api.config;

import java.util.Locale;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;

/**
 * Represent the security systems basic permissions, these are not for anti-griefing, they are part of the mod as a
 * gameplay feature.
 */
public enum SecurityPermissions {
    /**
     * required to insert items into the network via terminal ( also used for machines based on the owner of the
     * network, which is determined by its Security Block. )
     */
    INJECT,

    /**
     * required to extract items from the network via terminal ( also used for machines based on the owner of the
     * network, which is determined by its Security Block. )
     */
    EXTRACT,

    /**
     * required to request crafting from the network via terminal.
     */
    CRAFT,

    /**
     * required to modify automation, and make modifications to the networks physical layout.
     */
    BUILD,

    /**
     * required to modify the security blocks settings.
     */
    SECURITY;

    private final String translationKey = "gui.appliedenergistics2.security." + this.name().toLowerCase(Locale.ROOT);

    private final ITextComponent displayName = new TranslationTextComponent(this.translationKey + ".name");

    private final ITextComponent displayHint = new TranslationTextComponent(this.translationKey + ".tip");

    public ITextComponent getDisplayName() {
        return this.displayName;
    }

    public ITextComponent nameText() {
        return new TranslationTextComponent(getTranslatedName());
    }

    public ITextComponent getDisplayHint() {
        return this.displayHint;
    }

    public ITextComponent tooltipText() {
        return new TranslationTextComponent(getTranslatedTip());
    }

}
