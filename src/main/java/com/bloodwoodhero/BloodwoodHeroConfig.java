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
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(BloodwoodHeroConfig.GROUP)
public interface BloodwoodHeroConfig extends Config
{
	String GROUP = "bloodwoodhero";

	@ConfigSection(
		name = "Timing",
		description = "How far ahead the lines are drawn, and how many clicks each beat asks for.",
		position = 0,
		closedByDefault = true
	)
	String trackSection = "trackSection";

	@ConfigSection(
		name = "Colours",
		description = "The lanes, the hit line and the flash when a chop lands.",
		position = 1,
		closedByDefault = true
	)
	String colourSection = "colourSection";

	@ConfigSection(
		name = "Scoring",
		description = "The combo, the score, and how strict the timing is.",
		position = 2,
		closedByDefault = true
	)
	String scoreSection = "scoreSection";

	@ConfigSection(
		name = "Sap",
		description = "The sap left in each tree, shown inside its clickbox.",
		position = 3,
		closedByDefault = true
	)
	String sapSection = "sapSection";

	@ConfigItem(
		position = 0,
		keyName = "showSap",
		name = "Show sap remaining",
		description = "Print the sap left in each bleeding tree inside its own clickbox, coloured by how full it is. Shown whether or not you are chopping, since the point of it is seeing at a glance which tree still has something in it.",
		section = sapSection
	)
	default boolean showSap()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "sapFontSize",
		name = "Sap text size",
		description = "Size of the number drawn above each tree's sap bar.",
		section = sapSection
	)
	@Range(min = 8, max = 48)
	default int sapFontSize()
	{
		return 24;
	}

	@Alpha
	@ConfigItem(
		position = 2,
		keyName = "sapFullColor",
		name = "Full",
		description = "Colour while the tree is at least two thirds of the most sap it has held since it started bleeding. The fullness is measured against that rather than against a fixed number, since the game publishes no maximum.",
		section = sapSection
	)
	default Color sapFullColor()
	{
		return new Color(110, 255, 120);
	}

	@Alpha
	@ConfigItem(
		position = 3,
		keyName = "sapHalfColor",
		name = "Half",
		description = "Colour between a third and two thirds full.",
		section = sapSection
	)
	default Color sapHalfColor()
	{
		return new Color(255, 225, 100);
	}

	@Alpha
	@ConfigItem(
		position = 4,
		keyName = "sapLowColor",
		name = "Running out",
		description = "Colour below a third full, which is the one worth noticing.",
		section = sapSection
	)
	default Color sapLowColor()
	{
		return new Color(255, 95, 95);
	}

	@Alpha
	@ConfigItem(
		position = 5,
		keyName = "collectColor",
		name = "Collect label",
		description = "Colour of the word drawn on a tree holding a full bucket. That click collects rather than chops, and shares its menu option with chopping, so nothing but this says which it is.",
		section = sapSection
	)
	default Color collectColor()
	{
		return new Color(110, 255, 120);
	}

	@Alpha
	@ConfigItem(
		position = 6,
		keyName = "chopLabelColor",
		name = "Chop label",
		description = "Colour of the word drawn on the tree being worked once it has nothing left to collect. Shown only for that tree: it is the resting state of every tree nobody is touching, and six of them saying so at once would be noise.",
		section = sapSection
	)
	default Color chopLabelColor()
	{
		return new Color(255, 95, 95);
	}

	@ConfigItem(
		position = 0,
		keyName = "lookaheadTicks",
		name = "Ticks of warning",
		description = "How many game ticks ahead the lines are drawn. Each tick of warning is one box height of travel, so a larger number starts the lines higher above the target rather than making them move faster.",
		section = trackSection
	)
	@Range(min = 2, max = 12)
	default int lookaheadTicks()
	{
		return 4;
	}

	@ConfigItem(
		position = 3,
		keyName = "showTimingBands",
		name = "Show the target line",
		description = "Draw the line through the middle of each window that the falling line should be clicked on, with the perfect and great bands shaded around it on the chop box. The bands are measured out of the same numbers the grade is decided by, so what is drawn and what is scored cannot disagree. Only the chop is graded, so only it carries the bands; the pull-back gets the line alone.",
		section = trackSection
	)
	default boolean showTimingBands()
	{
		return true;
	}

	@ConfigItem(
		position = 4,
		keyName = "showWhenIdle",
		name = "Show while not chopping",
		description = "Keep the boxes on screen between trees. With this off they appear once a chop lands and go once you stop, which keeps the area clear while you are banking or placing buckets.",
		section = trackSection
	)
	default boolean showWhenIdle()
	{
		return false;
	}

	@ConfigItem(
		position = 5,
		keyName = "showClickboxes",
		name = "Show character and tree markers",
		description = "Outline your character wherever it reaches outside the pull-back box, and mark where the other trees' boxes are. Your own model is part of what a pull-back click lands on, and it is the half that moves - swinging across the trunk and taking clicks meant for the wood, which otherwise looks like the chop box simply failing.",
		section = trackSection
	)
	default boolean showClickboxes()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		position = 0,
		keyName = "pullBackColor",
		name = "Pull-back colour",
		description = "Colour of the axe clickbox and the lines falling onto it.",
		section = colourSection
	)
	default Color pullBackColor()
	{
		return new Color(80, 170, 255);
	}

	@Alpha
	@ConfigItem(
		position = 1,
		keyName = "chopColor",
		name = "Chop colour",
		description = "Colour of the box over the tree and the line falling onto it.",
		section = colourSection
	)
	default Color chopColor()
	{
		return new Color(220, 50, 50);
	}

	@Alpha
	@ConfigItem(
		position = 2,
		keyName = "pulseColor",
		name = "Chop pulse",
		description = "Colour of the pulse that spreads across the ground out of the foot of the tree as a chop lands. A warm earth tone by default, so it reads as something happening to the floor rather than as an overlay drawn on top of it - the ground in Darkmeyer is already deep red, and anything red enough to stand out on it stops looking like it belongs there. The alpha sets how strong the pulse starts before it fades.",
		section = colourSection
	)
	default Color pulseColor()
	{
		return new Color(214, 158, 92, 190);
	}

	@ConfigItem(
		position = 0,
		keyName = "chopsPerTree",
		name = "Chops per tree",
		description = "The figure used before the first chop of a session, after which it is replaced by what your axe actually does. The real number varies by axe - twenty-four on an adamant down to eighteen on a crystal felling axe - so it is counted rather than configured once there is anything to count.",
		section = scoreSection
	)
	@Range(min = 1, max = 60)
	default int chopsPerTree()
	{
		return 22;
	}

	@ConfigItem(
		position = 2,
		keyName = "showScore",
		name = "Show the score panel",
		description = "Show a panel with the running score, the current and best combo, the chops into this tree and the calibrated window. Drag it wherever suits - it is a panel rather than text over your character so that none of it sits on the part of the screen the rest of the plugin is asking you to watch.",
		section = scoreSection
	)
	default boolean showScore()
	{
		return true;
	}

	@ConfigItem(
		position = 3,
		keyName = "showCombo",
		name = "Call out the combo",
		description = "Draw the combo in large text over the track as it climbs, the way a rhythm game would.",
		section = scoreSection
	)
	default boolean showCombo()
	{
		return true;
	}
}
