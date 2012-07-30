/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.encoders;

import com.jgoodies.forms.builder.DefaultFormBuilder;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import com.sun.jna.Platform;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.*;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import net.pms.Messages;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.RendererConfiguration;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAResource;
import net.pms.formats.Format;
import net.pms.io.OutputParams;
import net.pms.io.PipeProcess;
import net.pms.io.ProcessWrapper;
import net.pms.io.ProcessWrapperImpl;
import net.pms.network.HTTPResource;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use VLC as a backend transcoder. Note that 0.x and 1.x versions are unsupported 
 * (and probably will crash). Only the latest version will be supported
 * 
 * @author Leon Blakey <lord.quackstar@gmail.com>
 */
public class VLCVideo extends Player {
	private static final Logger LOGGER = LoggerFactory.getLogger(VLCVideo.class);
	protected final PmsConfiguration pmsconfig;
	public static final String ID = "vlctrans";
	protected JCheckBox hardwareAccel;
	protected JTextField audioPri;
	protected JTextField subtitlePri;
	protected JCheckBox subtitleEnabled;
	protected JTextField scale;
	protected final double scaleDefault = 1.0;
	protected JCheckBox codecOverride;
	protected JTextField codecVideo;
	protected JTextField codecAudio;
	protected JTextField codecContainer;
	protected JCheckBox experimentalCodecs;
	protected JCheckBox audioSyncEnabled;
	protected JTextField sampleRate;
	protected JTextField extraParams;

	public VLCVideo(PmsConfiguration pmsconfig) {
		this.pmsconfig = pmsconfig;
	}

	@Override
	public int purpose() {
		return VIDEO_SIMPLEFILE_PLAYER;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean isTimeSeekable() {
		return true;
	}

	@Override
	public boolean avisynth() {
		return false;
	}

	@Override
	public String[] args() {
		return new String[]{};
	}

	@Override
	public String name() {
		return "VLC Transcoder";
	}

	@Override
	public int type() {
		return Format.VIDEO;
	}

	@Override
	public String mimeType() {
		//I think?
		return HTTPResource.VIDEO_TRANSCODE;
	}

	@Override
	public String executable() {
		return pmsconfig.getVlcPath();
	}

	@Override
	public boolean isCompatible(DLNAResource resource) {
		//VLC is a general transcoder that should support every format
		//Until problem occurs, assume compatible
		return true;
	}

	/**
	 * Pick codecs for VLC based on formats the client supports;
	 * @param formats
	 * @return 
	 */
	protected CodecConfig genConfig(RendererConfiguration renderer) {
		CodecConfig config = new CodecConfig();
		if(renderer.isTranscodeToWMV()) {
			//Assume WMV = XBox = all media renderers with this flag
			LOGGER.debug("Using XBox WMV codecs");
			config.videoCodec = "wmv2";
			config.audioCodec = "wma";
			config.container = "asf";
		} else if(renderer.isTranscodeToMPEGPSAC3() || renderer.isTranscodeToMPEGTSAC3()) {
			//Default codecs for DLNA standard
			LOGGER.debug("Using DLNA standard codecs with " + renderer.getVideoTranscode().substring(0,2) + " container");
			config.videoCodec = "mp2v";
			config.audioCodec = "mp2a"; //NOTE: a52 sometimes causes audio to stop after ~5 mins
			config.container = renderer.isTranscodeToMPEGPSAC3() ? "ps" : "ts";
		}
		return config;
	}

	protected String getFirstMatch(Map<String, String> map, String[] needles) {
		for (String curNeedle : needles)
			if (map.containsKey(curNeedle))
				return map.get(curNeedle);
			else
				LOGGER.debug("Couldn't find " + curNeedle + " in " + map);
		return null;
	}

	public class CodecConfig {
		String videoCodec;
		String audioCodec;
		String container;
		String extraParams;
		String extraTrans;
	}

	protected List<String> getEncodingArgs(CodecConfig config) {
		//See: http://www.videolan.org/doc/streaming-howto/en/ch03.html
		//See: http://wiki.videolan.org/Codec
		//Xbox: wmv2, wma, asf (WORKING)
		//PS3: mp1v, mpga, mpeg1 (WORKING)
		List<String> args = new ArrayList<String>();

		//Codecs to use
		String videoCodec;
		String audioCodec;
		if (codecOverride.isSelected()) {
			videoCodec = codecVideo.getText();
			audioCodec = codecAudio.getText();
		} else {
			//Try to map codec
			videoCodec = config.videoCodec;
			audioCodec = config.audioCodec;
		}
		args.add("vcodec=" + videoCodec);
		args.add("acodec=" + audioCodec);

		//Bitrate in kbit/s (TODO: Use global option?)
		args.add("vb=4096");
		args.add("ab=128");

		//Video scaling
		args.add("scale=" + scale.getText());

		//Audio Channels
		args.add("channels=2");

		//Static sample rate
		args.add("samplerate=" + sampleRate.getText());

		//Recommended on VLC DVD encoding page
		args.add("keyint=16");

		//Recommended on VLC DVD encoding page
		args.add("strict-rc");

		//Stream subtitles to client
		//args.add("scodec=dvbs");
		//args.add("senc=dvbsub");

		//Hardcode subtitles into video
		args.add("soverlay");

		//This has caused garbled audio, so only enable when told to
		if (audioSyncEnabled.isSelected())
			args.add("audio-sync");

		return args;
	}

	protected String getMux(CodecConfig config) {
		if (codecOverride.isSelected())
			return codecContainer.getText();
		else
			return config.container;
	}

	@Override
	public ProcessWrapper launchTranscode(String fileName, DLNAResource dlna, DLNAMediaInfo media, OutputParams params) throws IOException {
		boolean isWindows = Platform.isWindows();

		//Make sure we can play this
		CodecConfig config = genConfig(params.mediaRenderer);

		PipeProcess tsPipe = new PipeProcess("VLC" + System.currentTimeMillis() + "." + getMux(config));
		ProcessWrapper pipe_process = tsPipe.getPipeProcess();

		LOGGER.debug("filename: " + fileName);
		LOGGER.debug("dlna: " + dlna);
		LOGGER.debug("media: " + media);
		LOGGER.debug("outputparams: " + params);

		// XXX it can take a long time for Windows to create a named pipe
		// (and mkfifo can be slow if /tmp isn't memory-mapped), so start this as early as possible
		pipe_process.runInNewThread();
		tsPipe.deleteLater();

		params.input_pipes[0] = tsPipe;
		params.minBufferSize = params.minFileSize;
		params.secondread_minsize = 100000;

		List<String> cmdList = new ArrayList<String>();
		cmdList.add(executable());
		cmdList.add("-I");
		cmdList.add("dummy");

		//Hardware acceleration seems to be more stable now, so its enabled
		if (hardwareAccel.isSelected())
			cmdList.add("--ffmpeg-hw");

		//Useful for the more esoteric codecs people use
		if (experimentalCodecs.isSelected())
			cmdList.add("--sout-ffmpeg-strict=-2");

		//Stop the DOS box from appearing on windows
		if (isWindows)
			cmdList.add("--dummy-quiet");

		//File needs to be given before sout, otherwise vlc complains
		cmdList.add(fileName);

		//Handle audio language
		if (params.aid != null)
			//User specified language at the client, acknowledge it
			if (params.aid.getLang() == null || params.aid.getLang().equals("und"))
				//VLC doesn't understand und, but does understand none
				cmdList.add("--audio-language=none");
			else
				//Load by ID (better)
				cmdList.add("--audio-track=" + params.aid.getId());
		else
			//Not specified, use language from GUI
			cmdList.add("--audio-language=" + audioPri.getText());

		//Handle subtitle language
		if (params.sid != null)
			//User specified language at the client, acknowledge it
			if (params.sid.getLang() == null || params.sid.getLang().equals("und"))
				//VLC doesn't understand und, but does understand none
				cmdList.add("--sub-language=none");
			else
				//Load by ID (better)
				cmdList.add("--sub-track=" + params.sid.getId());
		else if (subtitleEnabled.isSelected())
			//Not specified, use language from GUI if enabled
			cmdList.add("--sub-language=" + subtitlePri.getText());
		else
			cmdList.add("--sub-language=none");

		//Skip forward if nessesary
		if (params.timeseek != 0) {
			cmdList.add("--start-time");
			cmdList.add("" + params.timeseek);
		}

		//Add our transcode options
		String transcodeSpec = String.format(
				"#transcode{%s}:std{access=file,mux=%s,dst=\"%s%s\"}",
				StringUtils.join(getEncodingArgs(config), ","),
				getMux(config),
				(isWindows ? "\\\\" : ""),
				tsPipe.getInputPipe());
		cmdList.add("--sout");
		cmdList.add(transcodeSpec);

		//Force VLC to die when finished
		cmdList.add("vlc://quit");

		//Add any extra parameters
		if (!extraParams.getText().trim().isEmpty())
			//Add each part as a new item
			cmdList.addAll(Arrays.asList(StringUtils.split(extraParams.getText().trim(), " ")));

		//Pass to process wrapper
		String[] cmdArray = new String[cmdList.size()];
		cmdList.toArray(cmdArray);
		cmdArray = finalizeTranscoderArgs(this, fileName, dlna, media, params, cmdArray);
		LOGGER.debug("Finalized args: " + StringUtils.join(cmdArray, " "));
		ProcessWrapperImpl pw = new ProcessWrapperImpl(cmdArray, params);
		pw.attachProcess(pipe_process);

		//TODO: Why is this here?
		try {
			Thread.sleep(150);
		} catch (InterruptedException e) {
		}

		pw.runInNewThread();
		return pw;
	}

	@Override
	public JComponent config() {
		//Here goes my 3rd try to learn JGoodies Form
		FormLayout layout = new FormLayout(
				"right:pref, 3dlu, pref:grow, 7dlu, right:pref, 3dlu, pref:grow", //columns
				""); //rows (none, dynamic)
		layout.setColumnGroups(new int[][]{{1, 5}, {3, 7}});
		DefaultFormBuilder mainPanel = new DefaultFormBuilder(layout);

		mainPanel.appendSeparator(Messages.getString("VlcTrans.1"));
		mainPanel.append(hardwareAccel = new JCheckBox(Messages.getString("VlcTrans.2"), pmsconfig.isVlcUseHardwareAccel()), 3);
		hardwareAccel.setContentAreaFilled(false);
		hardwareAccel.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setVlcUseHardwareAccel(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.append(experimentalCodecs = new JCheckBox(Messages.getString("VlcTrans.3"), pmsconfig.isExperimentalCodecs()), 3);
		experimentalCodecs.setContentAreaFilled(false);
		experimentalCodecs.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setExperimentalCodecs(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.append(audioSyncEnabled = new JCheckBox(Messages.getString("VlcTrans.4"), pmsconfig.isAudioSyncEnabled()), 3);
		audioSyncEnabled.setContentAreaFilled(false);
		audioSyncEnabled.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setAudioSyncEnabled(e.getStateChange() == ItemEvent.SELECTED);
			}
		});
		mainPanel.append(subtitleEnabled = new JCheckBox(Messages.getString("VlcTrans.5"), pmsconfig.isSubtitleEnabled()), 3);
		subtitleEnabled.setContentAreaFilled(false);
		subtitleEnabled.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				pmsconfig.setSubtitleEnabled(e.getStateChange() == ItemEvent.SELECTED);
			}
		});

		mainPanel.append(Messages.getString("VlcTrans.6"), audioPri = new JTextField(pmsconfig.getAudioPri()));
		audioPri.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				pmsconfig.setAudioPri(audioPri.getText());
			}
		});
		mainPanel.append(Messages.getString("VlcTrans.8"), subtitlePri = new JTextField(pmsconfig.getSubtitlePri()));
		subtitlePri.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				pmsconfig.setSubtitlePri(subtitlePri.getText());
			}
		});

		//Developer stuff. Theoretically is temporary 
		mainPanel.appendSeparator("Advanced Settings");

		//Add scale as a subpanel because it has an awkward layout
		mainPanel.append("Video scale: ");
		FormLayout scaleLayout = new FormLayout("pref,3dlu,pref", "");
		DefaultFormBuilder scalePanel = new DefaultFormBuilder(scaleLayout);
		scalePanel.append(scale = new JTextField("" + scaleDefault));
		final JSlider scaleSlider = new JSlider(JSlider.HORIZONTAL, 0, 10, (int) (scaleDefault * 10));
		scalePanel.append(scaleSlider);
		Hashtable<Integer, JLabel> scaleLabels = new Hashtable<Integer, JLabel>();
		scaleLabels.put(0, new JLabel("0.0"));
		scaleLabels.put(5, new JLabel("0.5"));
		scaleLabels.put(10, new JLabel("1.0"));
		scaleSlider.setLabelTable(scaleLabels);
		scaleSlider.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent ce) {
				scale.setText(String.valueOf((double) scaleSlider.getValue() / 10));
			}
		});
		scale.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				String typed = scale.getText();
				scaleSlider.setValue(0);
				if (!typed.matches("\\d+") || typed.length() > 3)
					return;
				scaleSlider.setValue(Integer.parseInt(typed) * 10);
			}
		});
		mainPanel.append(scalePanel.getPanel(), 3);

		//Allow user to choose codec
		mainPanel.nextLine();
		FormLayout codecLayout = new FormLayout(
				"right:pref, 3dlu, right:pref, 3dlu, pref:grow, 7dlu, right:pref, 3dlu, pref:grow, 7dlu, right:pref, 3dlu, pref:grow", //columns
				"bottom:pref:grow, 3dlu, top:pref:grow"); //rows
		codecLayout.setColumnGroups(new int[][]{{5, 9, 13}, {3, 7, 11}});
		codecLayout.setRowGroups(new int[][]{{1, 3}});
		DefaultFormBuilder codecPanel = new DefaultFormBuilder(codecLayout);
		CellConstraints cc = new CellConstraints();
		codecPanel.add(new JLabel("<html>Codecs that VLC will use. <br>Good places to start:"
				+ "<br> XBox: wmv2, wma, asf"
				+ "<br> PS3: mp1v, mpga, mpeg</html>"), cc.xywh(1, 1, 1, 3));
		codecPanel.add(codecOverride = new JCheckBox("Override codec autodetection"), cc.xyw(3, 1, 3));
		codecPanel.addLabel("Video codec: ", cc.xy(3, 3));
		codecPanel.add(codecVideo = new JTextField(""), cc.xy(5, 3));
		codecPanel.addLabel("Audio codec: ", cc.xy(7, 3));
		codecPanel.add(codecAudio = new JTextField(""), cc.xy(9, 3));
		codecPanel.addLabel("Container: ", cc.xy(11, 3));
		codecPanel.add(codecContainer = new JTextField(""), cc.xy(13, 3));
		toggleCodecVisibility(false);
		codecOverride.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				toggleCodecVisibility(((JCheckBox) e.getSource()).isSelected());
			}
		});
		mainPanel.append(codecPanel.getPanel(), 7);

		//Audio sample rate
		mainPanel.append("<html>Audio sample rate<br>Potential Values: 44100 (unstable), 48000", sampleRate = new JTextField("48000"));

		//Extra options
		mainPanel.nextLine();
		mainPanel.append("Extra parameters: ", extraParams = new JTextField(), 5);

		return mainPanel.getPanel();
	}

	protected void toggleCodecVisibility(boolean enabled) {
		codecVideo.setEnabled(enabled);
		codecAudio.setEnabled(enabled);
		codecContainer.setEnabled(enabled);
	}
}
