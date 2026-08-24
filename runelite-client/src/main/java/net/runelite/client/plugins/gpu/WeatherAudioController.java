package net.runelite.client.plugins.gpu;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WeatherAudioController
{
	private static final String GENTLE_RAIN = "weather/audio/loops/rain_gentle_loop.wav";
	private static final String STORM_RAIN = "weather/audio/loops/rain_storm_loop.wav";
	private static final String CLOSE_LIGHTNING = "weather/audio/thunder/lightning_close_01.wav";
	private static final String ROLLING_THUNDER = "weather/audio/thunder/thunder_lightning_01.wav";

	private Clip activeLoop;
	private String activeLoopResource;
	private Clip thunderClip;
	private boolean audioUnavailable;

	void update(WeatherMode mode, boolean enabled, int volume)
	{
		String desiredLoop = enabled && volume > 0 ? loopFor(mode) : null;
		if (!equals(activeLoopResource, desiredLoop))
		{
			close(activeLoop);
			activeLoop = null;
			activeLoopResource = desiredLoop;
			if (desiredLoop != null && !audioUnavailable)
			{
				activeLoop = open(desiredLoop);
				if (activeLoop != null)
				{
					setVolume(activeLoop, volume);
					activeLoop.loop(Clip.LOOP_CONTINUOUSLY);
				}
			}
		}
		else if (activeLoop != null)
		{
			setVolume(activeLoop, volume);
		}
	}

	void playThunder(long lightningCycle, int volume)
	{
		if (audioUnavailable || volume <= 0)
		{
			return;
		}

		close(thunderClip);
		String resource = (lightningCycle & 1L) == 0L ? CLOSE_LIGHTNING : ROLLING_THUNDER;
		thunderClip = open(resource);
		if (thunderClip != null)
		{
			setVolume(thunderClip, volume);
			thunderClip.setFramePosition(0);
			thunderClip.start();
		}
	}

	void shutdown()
	{
		close(activeLoop);
		close(thunderClip);
		activeLoop = null;
		thunderClip = null;
		activeLoopResource = null;
	}

	private Clip open(String resource)
	{
		InputStream raw = WeatherAudioController.class.getResourceAsStream(resource);
		if (raw == null)
		{
			log.warn("Weather audio resource is missing: {}", resource);
			return null;
		}

		try (BufferedInputStream buffered = new BufferedInputStream(raw);
			 AudioInputStream audio = AudioSystem.getAudioInputStream(buffered))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(audio);
			return clip;
		}
		catch (UnsupportedAudioFileException | IOException e)
		{
			log.warn("Unable to decode weather audio {}: {}", resource, e.getMessage());
		}
		catch (LineUnavailableException | IllegalArgumentException e)
		{
			audioUnavailable = true;
			log.warn("Weather audio output is unavailable: {}", e.getMessage());
		}
		return null;
	}

	private static String loopFor(WeatherMode mode)
	{
		if (mode == WeatherMode.RAIN)
		{
			return GENTLE_RAIN;
		}
		if (mode == WeatherMode.STORM)
		{
			return STORM_RAIN;
		}
		return null;
	}

	private static void setVolume(Clip clip, int volume)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		float linear = Math.max(0.0001f, Math.min(1.0f, volume / 100.0f));
		float decibels = 20.0f * (float) Math.log10(linear);
		gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
	}

	private static void close(Clip clip)
	{
		if (clip != null)
		{
			clip.stop();
			clip.close();
		}
	}

	private static boolean equals(String a, String b)
	{
		return a == null ? b == null : a.equals(b);
	}
}
