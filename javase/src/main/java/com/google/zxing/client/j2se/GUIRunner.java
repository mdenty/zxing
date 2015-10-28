/*
 * Copyright 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.j2se;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * <p>Simple GUI frontend to the library. Right now, only decodes a local file.
 * This definitely needs some improvement. Just throwing something down to start.</p>
 *
 * @author Sean Owen
 */
public final class GUIRunner extends JFrame {

  public static final String ZING_GUI = ".zing-gui";
  public static final String LAST_PATH = "lastPath";
  private final JLabel imageLabel;
  private final JTextComponent textArea;
  private final Properties prefs = new Properties();

  private GUIRunner() {
    imageLabel = new JLabel();
    textArea = new JTextArea();
    textArea.setEditable(false);
    textArea.setMaximumSize(new Dimension(400, 200));
    Container panel = new JPanel();
    panel.setLayout(new FlowLayout());
    panel.add(imageLabel);
    panel.add(textArea);
    setTitle("ZXing");
    setSize(400, 400);
    setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    setContentPane(panel);
    setLocationRelativeTo(null);
  }

  public static void main(String[] args) throws IOException {
    final GUIRunner runner = new GUIRunner();
    runner.setVisible(true);
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        try {
          runner.chooseImage();
        } catch (IOException e) {
          e.printStackTrace();
          System.exit(1);
        }
      }
    });
  }

  private final synchronized void savePrefs() throws IOException {
    final String homeDir = System.getProperty("user.home");
    final File prefFile = new File(homeDir, ZING_GUI);
    try(FileOutputStream out = new FileOutputStream(prefFile)) {
      prefs.storeToXML(out, "Zxing-gui preferences");
    }
  }

  private void chooseImage() throws IOException {
    final JFileChooser fileChooser = new JFileChooser();
    fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);

    final String homeDir = System.getProperty("user.home");
    final File prefFile = new File(homeDir, ZING_GUI);
    if (prefFile.exists()) {
      try(FileInputStream in = new FileInputStream(prefFile)) {
        prefs.loadFromXML(in);
      }
    }
    final String initPath = prefs.getProperty(LAST_PATH, homeDir);
    fileChooser.setCurrentDirectory(new File(initPath));

    fileChooser.showOpenDialog(this);
    Path file = fileChooser.getSelectedFile().toPath();
    Icon imageIcon = new ImageIcon(file.toUri().toURL());
    setSize(imageIcon.getIconWidth(), imageIcon.getIconHeight() + 100);
    imageLabel.setIcon(imageIcon);
    if (Files.isDirectory(file)) {
      prefs.setProperty(LAST_PATH, file.toString());
      savePrefs();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(file)) {
        for (Path entry : stream) {
          if (Files.isDirectory(entry)) {
            continue;
          }
          String decodeText = getDecodeText(entry);
          System.out.println(String.format("%s : %s", entry.getFileName(), decodeText));
        }
      }
    } else {
      prefs.setProperty(LAST_PATH, file.getParent().toString());
      savePrefs();

      String decodeText = getDecodeText(file);
      System.out.println(String.format("%s : %s", file, decodeText));
      textArea.setText(decodeText);
    }
  }

  private static String getDecodeText(Path file) {
    BufferedImage image;
    try {
      image = ImageReader.readImage(file.toUri());
    } catch (IOException ioe) {
      return ioe.toString();
    }
    LuminanceSource source = new BufferedImageLuminanceSource(image);
    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
    Result result;
    try {
      result = new DataMatrixReader().decode(bitmap);
    } catch (ReaderException re) {
      return re.toString();
    }
    return String.valueOf(result.getText());
  }

}
