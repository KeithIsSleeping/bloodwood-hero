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

import lombok.Getter;

/**
 * The two things a chop cycle asks of you, one per game tick.
 *
 * <p>A bloodwood chop is not one action but a pair of them on consecutive ticks: the axe
 * is pulled back by clicking yourself twice within a single tick, and the chop itself is
 * a single click on the tree on the tick after. Both have to land on their own tick, so
 * they are modelled as separate beats rather than as one action with a wind-up.</p>
 */
public enum BloodwoodBeat
{
	/** Two clicks on your own character, within one tick, to pull the axe back. */
	PULL_BACK("PULL", 2),

	/** One click on the tree, on the tick after the pull-back. */
	CHOP("CHOP", 1);

	/** Short label drawn on the note. */
	@Getter
	private final String label;

	/** Clicks this beat wants inside its tick. Getting the count wrong breaks the cycle. */
	@Getter
	private final int clicks;

	BloodwoodBeat(String label, int clicks)
	{
		this.label = label;
		this.clicks = clicks;
	}

	/**
	 * How far apart the two clicks of a pull-back are, as a fraction of a tick.
	 *
	 * <p>Tightened from a third of a tick, which was far too loose to be clicked as one
	 * movement - it asked for two deliberate clicks a fifth of a second apart and then
	 * punished the pair for not fitting. This is a quick double click, which is what the
	 * action actually is.</p>
	 *
	 * <p>It belongs here rather than in the overlay because it is not only a drawing
	 * decision. The window a click must land in has to leave room for the second one, so
	 * the same figure sets the spacing of the notes and the size of the window they fall
	 * through, and the two cannot be allowed to disagree.</p>
	 */
	public static final double CLICK_SPACING = 0.15;
}
