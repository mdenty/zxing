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
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <p>Simple GUI frontend to the library. Right now, only decodes a local file.
 * This definitely needs some improvement. Just throwing something down to start.</p>
 *
 * @author Sean Owen
 */
public final class GUIRunner extends JFrame {

  private final JLabel imageLabel;
  private final JTextComponent textArea;

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
    GUIRunner runner = new GUIRunner();
    runner.setVisible(true);
    runner.chooseImage();
  }

  private void chooseImage() throws IOException {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
    //fileChooser.setCurrentDirectory(new File("/home/abonnet/Workspace/projects/zxing/core/src/test/resources/blackbox/datamatrix-2"));
    //fileChooser.setCurrentDirectory(new File("/home/abonnet/Workspace/projects/TessiMobile/docs"));
    fileChooser.showOpenDialog(this);
    Path file = fileChooser.getSelectedFile().toPath();
    Icon imageIcon = new ImageIcon(file.toUri().toURL());
    setSize(imageIcon.getIconWidth(), imageIcon.getIconHeight() + 100);
    imageLabel.setIcon(imageIcon);
    if (Files.isDirectory(file)) {
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
