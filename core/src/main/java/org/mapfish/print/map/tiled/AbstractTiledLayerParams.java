package org.mapfish.print.map.tiled;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import org.apache.commons.lang3.StringUtils;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.mapfish.print.map.AbstractLayerParams;
import org.mapfish.print.parser.HasDefaultValue;
import org.gvsig.mvtrenderer.lib.impl.MVTStyles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contains the standard parameters for tiled layers. */
public abstract class AbstractTiledLayerParams extends AbstractLayerParams {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTiledLayerParams.class);

  /**
   * The name of the style (in Configuration or Template) to use when drawing the layer to the map.
   * This is separate from the style in that it indicates how to draw the map. It allows one to
   * apply any of the SLD raster styling.
   */
  @HasDefaultValue public String rasterStyle = "raster";
  /**
   * FIXME: Agregar javadoc de vectorStyles.
   */
  @HasDefaultValue public String vectorStyles = null;
  /**
   * FIXME: Agregar javadoc de vectorTileSize.
   */
  @HasDefaultValue public Integer vectorTileSize = null;
  
  private MVTStyles styles = null;

  /** Constructor. */
  protected AbstractTiledLayerParams() {
    super();
  }

  /**
   * Copy constructor.
   *
   * @param other the object to copy
   */
  protected AbstractTiledLayerParams(final AbstractTiledLayerParams other) {
    super(other);
    this.rasterStyle = other.rasterStyle;
  }

  /**
   * Get the base url for all tile requests. For example it might be 'http://server
   * .com/geoserver/gwc/service/wmts'.
   */
  public abstract String getBaseUrl();

  /**
   * Validates the provided base url.
   *
   * @return True, if the url is valid.
   */
  public abstract boolean validateBaseUrl();

  /**
   * Create a URL that is common to all image requests for this layer. It will take the base url and
   * append all mergeable and custom params to the base url.
   */
  public abstract String createCommonUrl() throws URISyntaxException;

  public CoordinateReferenceSystem getCRS() {
    return null;
  }
  
  public URL getVectorStylesURL() throws MalformedURLException {
    if(StringUtils.isBlank(this.vectorStyles)) {
      return null;
    } 
    return new URL(this.vectorStyles);
  }
  
  public MVTStyles getVectorStyles() {
    if(this.styles != null) {
      return this.styles;
    }
    try {
      URL url = this.getVectorStylesURL();
      MVTStyles theStyles = new MVTStyles();
      theStyles.download(url);
      LOGGER.info("Fonts used by '"+url.toString()+"':"+StringUtils.join(theStyles.getUsedFontNames(),","));
      this.styles = theStyles;
      return this.styles;
    } catch (Exception e) {
      return null;
    }
  }
}
