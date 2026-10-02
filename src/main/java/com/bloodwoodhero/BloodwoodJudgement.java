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
import lombok.Getter;

/**
 * How a chop is graded, and what it is worth.
 *
 * <p>The grade is honest about what actually happened rather than decorative. Anything
 * short of {@link #BAD} is a chop that landed on the beat and advanced the tree; BAD is
 * only ever given when the rhythm genuinely broke and a cycle was lost. The three good
 * grades separate on how close the click was to the middle of its window, which is the
 * part the player can actually feel and improve.</p>
 */
public enum BloodwoodJudgement
{
	/** Dead centre of the window. */
	PERFECT("PERFECT!", new Color(120, 255, 255), 100),

	/** Comfortably inside it. */
	GREAT("GREAT", new Color(120, 255, 140), 60),

	/** Inside it, but near an edge - the cycle survived with little to spare. */
	OK("OK", new Color(255, 230, 120), 30),

	/** The cycle broke: the chop arrived late and the combo is gone. */
	BAD("MISSED", new Color(255, 90, 90), 0);

	@Getter
	private final String label;

	@Getter
	private final Color color;

	/** Points before the combo multiplier. */
	@Getter
	private final int points;

	BloodwoodJudgement(String label, Color color, int points)
	{
		this.label = label;
		this.color = color;
		this.points = points;
	}

	/**
	 * How far from the middle of the window a click may sit and still be perfect.
	 *
	 * <p>Tightened by about a third now that the thing being aimed at holds still. These
	 * were widened because the window drifted underneath them - it was being guessed at
	 * from play, so the instant a perfect click landed on moved as the guess moved, and
	 * asking for precision against that was asking for more than the target could offer.
	 * The window is now the usable part of a tick less the room the second click needs,
	 * which is a fixed quantity that only moves with the connection, so the slack that
	 * covered the drift is no longer paying for anything.</p>
	 *
	 * <p>Public because the overlay draws the band from it. Drawing the target from the
	 * same number the grade is decided by is the only way the two cannot disagree - a
	 * second copy would drift the moment either was tuned.</p>
	 */
	public static final double PERFECT_WINDOW = 0.151;

	/**
	 * The same, for a great rather than a perfect click.
	 *
	 * <p>Tightened by the same third, which also puts it back clear of the edge. The
	 * offset this is measured against runs to 0.5 at the edge of the window, and the
	 * previous figure sat close enough to that to leave OK almost no room - a grade that
	 * is hardly ever given is hardly a grade.</p>
	 */
	public static final double GREAT_WINDOW = 0.315;

	/**
	 * Grades a chop that landed on the beat by how centred its click was.
	 *
	 * @param offset how far the click sat from the middle of its window, 0 at the centre
	 *               and 0.5 at either edge
	 */
	public static BloodwoodJudgement fromOffset(double offset)
	{
		double from = Math.abs(offset);
		if (from <= PERFECT_WINDOW)
		{
			return PERFECT;
		}
		return from <= GREAT_WINDOW ? GREAT : OK;
	}
}
