/*
 * Copyright (c) 2026, KeithIsSleeping
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.bloodwoodhero;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.NumberFormat;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The running totals, in a panel of their own rather than over the character.
 *
 * <p>These were drawn under the pull-back window, which put a line of text across the
 * player and the ground either side of them - the one part of the screen the whole plugin
 * is asking you to watch. None of it needs to be read on the tick: a score, a record and
 * a calibration figure are all things to glance at between trees, so they belong
 * somewhere they can be glanced at and otherwise ignored.</p>
 */
class BloodwoodHeroPanelOverlay extends OverlayPanel
{
	private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance();
	private static final Color GOOD = new Color(120, 255, 140);

	private final BloodwoodHeroPlugin plugin;
	private final BloodwoodHeroConfig config;

	/**
	 * The panel's rows, built once and then only have their values changed.
	 *
	 * <p>Rebuilding the whole tree every frame meant six builders, six components and
	 * each component's own sizing objects fifty times a second, for a panel whose shape
	 * never changes. Only the figures on the right change, and those can be set in
	 * place.</p>
	 */
	private final TitleComponent title;
	private final LineComponent scoreLine;
	private final LineComponent comboLine;
	private final LineComponent bestLine;
	private final LineComponent chopsLine;
	private final LineComponent windowLine;

	@Inject
	private BloodwoodHeroPanelOverlay(BloodwoodHeroPlugin plugin, BloodwoodHeroConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);

		// The children are kept between frames rather than cleared and rebuilt.
		setClearChildren(false);

		title = TitleComponent.builder().text("Bloodwood Hero").build();
		scoreLine = LineComponent.builder().left("Score").build();
		comboLine = LineComponent.builder().left("Combo").build();
		bestLine = LineComponent.builder().left("Best").build();
		chopsLine = LineComponent.builder().left("Chops").build();
		windowLine = LineComponent.builder().left("Window").rightColor(GOOD).build();

		panelComponent.setPreferredSize(new Dimension(150, 0));
		panelComponent.getChildren().add(title);
		panelComponent.getChildren().add(scoreLine);
		panelComponent.getChildren().add(comboLine);
		panelComponent.getChildren().add(bestLine);
		panelComponent.getChildren().add(chopsLine);
		panelComponent.getChildren().add(windowLine);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showScore() || (!config.showWhenIdle() && !plugin.isChopping()))
		{
			return null;
		}

		title.setColor(config.chopColor());
		scoreLine.setRight(NUMBERS.format(plugin.getScore()));

		// Coloured by the same tiers the combo wears over the character, so the panel and
		// the scene agree about what a given run is worth.
		comboLine.setRight(plugin.getCombo() + "x");
		comboLine.setRightColor(plugin.getComboColor());

		bestLine.setRight(plugin.getBestCombo() + "x");
		chopsLine.setRight(plugin.getTreeChops() + "/" + plugin.getChopsPerTree());

		// Shown with the ping it is derived from, so the figure can be sanity-checked
		// without opening the settings: the window is the whole tick less the round trip,
		// and seeing both makes it obvious when a bad connection is the thing costing
		// the player the bottom of the box.
		int ping = plugin.getPingMillis();
		windowLine.setRight(ping > 0
			? Math.round(plugin.effectiveWindow() * 100) + "% (" + ping + "ms)"
			: "measuring");

		return super.render(graphics);
	}
}
