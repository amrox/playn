/**
 * Copyright 2010 The PlayN Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package playn.java;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.imageio.ImageIO;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;

import pythagoras.f.MathUtil;

import playn.core.AbstractAssets;
import playn.core.AsyncImage;
import playn.core.Image;
import playn.core.Sound;
import playn.core.gl.Scale;

/**
 * Loads Java assets via the classpath.
 */
public class JavaAssets extends AbstractAssets<BufferedImage> {

  private final JavaPlatform platform;
  private File[] directories = {};

  private String pathPrefix = "assets/";
  private Scale assetScale = null;

  /**
   * Creates a new java assets.
   */
  public JavaAssets(JavaPlatform platform) {
    super(platform);
    this.platform = platform;
  }

  /**
   * Configures the prefix prepended to asset paths before fetching them from the classpath. For
   * example, if your assets are in {@code src/main/java/com/mygame/assets} (or in {@code
   * src/main/resources/com/mygame/assets}), you can pass {@code com/mygame/assets} to this method
   * and then load your assets without prefixing their path with that value every time. The value
   * supplied to this method should not contain leading or trailing slashes. Note that this prefix
   * should always use '/' as a path separator as it is used to construct URLs, not filesystem
   * paths.
   * <p>NOTE: the path prefix is not used when searching extra directories</p>
   */
  public void setPathPrefix(String prefix) {
    if (prefix.startsWith("/") || prefix.endsWith("/")) {
      throw new IllegalArgumentException("Prefix must not start or end with '/'.");
    }
    pathPrefix = (prefix.length() == 0) ? prefix : (prefix + "/");
  }

  /**
   * Returns the currently configured path prefix. Note that this value will always have a trailing
   * slash.
   */
  public String getPathPrefix() {
    return pathPrefix;
  }

  /**
   * Adds the given directory to the search path for resources.
   * <p>TODO: remove? get?</p>
   */
  public void addDirectory(File dir) {
    File[] ndirs = new File[directories.length + 1];
    System.arraycopy(directories, 0, ndirs, 0, directories.length);
    ndirs[ndirs.length - 1] = dir;
    directories = ndirs;
  }

  /**
   * Configures the default scale to use for assets. This allows one to specify an intermediate
   * graphics scale (like 1.5) and scale the 2x imagery down to 1.5x instead of scaling the 1.5x
   * imagery up (or displaying nothing at all).
   */
  public void setAssetScale(float scaleFactor) {
    this.assetScale = new Scale(scaleFactor);
  }

  @Override
  public Image getRemoteImage(final String url, float width, float height) {
    final JavaAsyncImage image = platform.graphics().createAsyncImage(width, height);
    platform.invokeAsync(new Runnable() {
      public void run () {
        try {
          setImageLater(image, ImageIO.read(new URL(url)), Scale.ONE);
        } catch (Exception error) {
          setErrorLater(image, error);
        }
      }
    });
    return image;
  }

  @Override
  public Sound getSound(String path) {
    return getSound(path, false);
  }

  @Override
  public Sound getMusic(String path) {
    return getSound(path, true);
  }

  @Override
  public String getTextSync(String path) throws Exception {
    return Resources.toString(requireResource(path), Charsets.UTF_8);
  }

  @Override
  public byte[] getBytesSync(String path) throws Exception {
    return Resources.toByteArray(requireResource(path));
  }

  @Override
  protected Image createStaticImage(BufferedImage bufimg, Scale scale) {
    return platform.graphics().createStaticImage(bufimg, scale);
  }

  @Override
  protected AsyncImage<BufferedImage> createAsyncImage(float width, float height) {
    return platform.graphics().createAsyncImage(width, height);
  }

  @Override
  protected Image loadImage(String fullPath, ImageReceiver<BufferedImage> recv) {
    Exception error = null;
    for (Scale.ScaledResource rsrc : assetScale().getScaledResources(fullPath)) {
      try {
        BufferedImage image = ImageIO.read(requireResource(rsrc.path));
        // if image is at a higher scale factor than the view, scale to the view display factor
        Scale viewScale = platform.graphics().ctx().scale, imageScale = rsrc.scale;
        float viewImageRatio = viewScale.factor / imageScale.factor;
        if (viewImageRatio < 1) {
          image = scaleImage(image, viewImageRatio);
          imageScale = viewScale;
        }
        if (platform.convertImagesOnLoad) {
          BufferedImage convertedImage = JavaGLContext.convertImage(image);
          if (convertedImage != image) {
            platform.log().debug("Converted image: " + fullPath + " [type=" + image.getType() + "]");
            image = convertedImage;
          }
        }
        return recv.imageLoaded(image, imageScale);
      } catch (FileNotFoundException fnfe) {
        error = fnfe; // keep going, checking for lower resolution images
      } catch (Exception e) {
        error = e;
        break; // the image was broken not missing, stop here
      }
    }
    platform.log().warn("Could not load image: " + fullPath + " [error=" + error + "]");
    return recv.loadFailed(error != null ? error : new FileNotFoundException(fullPath));
  }

  /**
   * Attempts to locate the resource at the given path, and returns an input stream. First, the
   * path prefix is prepended (see {@link #setPathPrefix(String)}) and the the class loader checked.
   * If not found, then the extra directories, if any, are checked, in order. If the file is not
   * found in any of the extra directories either, then an exception is thrown.
   */
  protected InputStream getAssetStream(String path) throws IOException {
    InputStream in = getClass().getClassLoader().getResourceAsStream(pathPrefix + path);
    if (in != null) {
      return in;
    }
    for (File dir : directories) {
      File f = new File(dir, path);
      if (f.exists()) {
        return new FileInputStream(f);
      }
    }
    throw new FileNotFoundException();
  }

  protected Sound getSound(String path, boolean music) {
    Exception err = null;
    for (String suff : SUFFIXES) {
      final String soundPath = path + suff;
      try {
        return platform.audio().createSound(getAssetStream(soundPath), music);
      } catch (Exception e) {
        err = e; // note the error, and loop through and try the next format
      }
    }
    platform.log().warn("Sound load error " + path + ": " + err);
    return new Sound.Error(err);
  }

  /**
   * Attempts to locate the resource at the given path, and returns the URL. First, the path prefix
   * is prepended (see {@link #setPathPrefix(String)}) and the the class loader checked. If not
   * found, then the extra directories, if any, are checked, in order. If the file is not found in
   * any of the extra directories either, then an exception is thrown.
   */
  protected URL requireResource(String path) throws IOException {
    URL url = getClass().getClassLoader().getResource(pathPrefix + path);
    if (url != null) {
      return url;
    }
    for (File dir : directories) {
      File f = new File(dir, path).getCanonicalFile();
      if (f.exists()) {
        return f.toURI().toURL();
      }
    }
    throw new FileNotFoundException(path);
  }

  protected BufferedImage scaleImage(BufferedImage image, float viewImageRatio) {
    int swidth = MathUtil.iceil(viewImageRatio * image.getWidth());
    int sheight = MathUtil.iceil(viewImageRatio * image.getHeight());
    BufferedImage scaled = new BufferedImage(swidth, sheight, BufferedImage.TYPE_INT_ARGB_PRE);
    Graphics2D gfx = scaled.createGraphics();
    gfx.drawImage(image.getScaledInstance(swidth, sheight, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
    gfx.dispose();
    return scaled;
  }

  protected Scale assetScale() {
    return (assetScale != null) ? assetScale : platform.graphics().ctx().scale;
  }

  protected static final String[] SUFFIXES = { ".wav", ".mp3" };
}
