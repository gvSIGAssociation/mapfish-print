package org.mapfish.print.map.tiled;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RecursiveTask;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.io.EmptyInputStream;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.geometry.GeneralBounds;
import org.mapfish.print.PrintException;
import org.mapfish.print.StatsUtils;
import org.mapfish.print.config.Configuration;
import org.mapfish.print.map.style.json.ColorParser;
import org.mapfish.print.map.tiled.TilePreparationInfo.SingleTilePreparationInfo;
import org.mapfish.print.processor.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.gvsig.mvtrenderer.lib.impl.MVTTile;
import org.gvsig.mvtrenderer.lib.impl.MVTStyles;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;

/** The CoverageTask class. */
public final class CoverageTask implements Callable<GridCoverage2D> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CoverageTask.class);

  private final TileInformation<? extends AbstractTiledLayerParams> tiledLayer;
  private final TilePreparationInfo tilePreparationInfo;
  private final boolean failOnError;
  private final MetricRegistry registry;
  private final Processor.ExecutionContext context;
  private final BufferedImage errorImage;
  
  /**
   * Constructor.
   *
   * @param tilePreparationInfo tileLoader Results.
   * @param failOnError fail on tile download error.
   * @param registry the metrics registry.
   * @param context the job ID.
   * @param tileInformation the object used to create the tile requests.
   * @param configuration the configuration.
   */
  CoverageTask(
      @Nonnull final TilePreparationInfo tilePreparationInfo,
      final boolean failOnError,
      @Nonnull final MetricRegistry registry,
      @Nonnull final Processor.ExecutionContext context,
      @Nonnull final TileInformation<? extends AbstractTiledLayerParams> tileInformation,
      @Nonnull final Configuration configuration) {
    this.tilePreparationInfo = tilePreparationInfo;
    this.context = context;
    this.tiledLayer = tileInformation;
    this.failOnError = failOnError;
    this.registry = registry;

    final Dimension tileSize = this.tiledLayer.getTileSize();
    this.errorImage =
        new BufferedImage(tileSize.width, tileSize.height, BufferedImage.TYPE_4BYTE_ABGR);
    Graphics2D graphics = this.errorImage.createGraphics();
    try {
      graphics.setBackground(ColorParser.toColor(configuration.getOpaqueTileErrorColor()));
      graphics.clearRect(0, 0, tileSize.width, tileSize.height);
    } finally {
      graphics.dispose();
    }
  }
  
  /** Call the Coverage Task. */
  public GridCoverage2D call() {
    try {
      BufferedImage coverageImage =
          this.tiledLayer.createBufferedImage(
              this.tilePreparationInfo.getImageWidth(), this.tilePreparationInfo.getImageHeight());
      Graphics2D graphics = coverageImage.createGraphics();
      try {
        
      final double resolution = this.tiledLayer.getResolution();
      
        MVTTilesInfo vectorTilesInfo = new MVTTilesInfo(
                this.tiledLayer.getTileSize(),
                resolution,
                () -> { return tiledLayer.getMissingTileImage();},
                this.tilePreparationInfo.getMapProjection(),
                this.tiledLayer.getCRS(),
                this.tiledLayer.getVectorTileParams()
        );
        
        for (SingleTilePreparationInfo tileInfo : this.tilePreparationInfo.getSingleTiles()) {
          
          MVTTileInfo mvtTileInfo = vectorTilesInfo.createTileInfo(
                  this.tilePreparationInfo.getGridCoverageOrigin(), 
                  tileInfo.getTileIndexX(), 
                  tileInfo.getTileIndexY()
          ); 
          
          final Tile tile = getTile(tileInfo, mvtTileInfo);
          if (tile.getImage() != null) {
            // crop the image here
            BufferedImage noBufferTileImage;
            if (this.tiledLayer.getTileBufferWidth() > 0
                || this.tiledLayer.getTileBufferHeight() > 0) {
              int noBufferWidth =
                  Math.min(
                      this.tiledLayer.getTileSize().width,
                      tile.getImage().getWidth() - this.tiledLayer.getTileBufferWidth());
              int noBufferHeight =
                  Math.min(
                      this.tiledLayer.getTileSize().height,
                      tile.getImage().getHeight() - this.tiledLayer.getTileBufferHeight());
              noBufferTileImage =
                  tile.getImage()
                      .getSubimage(
                          this.tiledLayer.getTileBufferWidth(),
                          this.tiledLayer.getTileBufferHeight(),
                          noBufferWidth,
                          noBufferHeight);
            } else {
              noBufferTileImage = tile.getImage();
            }
            graphics.drawImage(
                noBufferTileImage,
                tile.getxIndex() * this.tiledLayer.getTileSize().width,
                tile.getyIndex() * this.tiledLayer.getTileSize().height,
                null);
          }
        }
      } finally {
        graphics.dispose();
      }

      GridCoverageFactory factory = CoverageFactoryFinder.getGridCoverageFactory(null);
      GeneralBounds gridEnvelope = new GeneralBounds(this.tilePreparationInfo.getMapProjection());
      gridEnvelope.setEnvelope(
          this.tilePreparationInfo.getGridCoverageOrigin().x,
          this.tilePreparationInfo.getGridCoverageOrigin().y,
          this.tilePreparationInfo.getGridCoverageMaxX(),
          this.tilePreparationInfo.getGridCoverageMaxY());
      return factory.create(
          this.tiledLayer.createCommonUrl(), coverageImage, gridEnvelope, null, null, null);
    } catch (URISyntaxException | UnsupportedEncodingException e) {
      throw new PrintException("Failed to call the coverage task", e);
    }
  }

  private Tile getTile(final SingleTilePreparationInfo tileInfo, MVTTileInfo mvtTileInfo) {
    final TileTask task;
    if (tileInfo.getTileRequest() != null) {
      task =
          new SingleTileLoaderTask(
              tileInfo.getTileRequest(),
              this.errorImage,
              tileInfo.getTileIndexX(),
              tileInfo.getTileIndexY(),
              this.failOnError,
              this.registry,
              this.context
          );
      ((SingleTileLoaderTask)task).setMVTTileInfo(mvtTileInfo);
    } else {
      task =
          new PlaceHolderImageTask(
              this.tiledLayer.getMissingTileImage(),
              tileInfo.getTileIndexX(),
              tileInfo.getTileIndexY());
    }
    return task.call();
  }
  
  private static class MVTTilesInfo {

    Dimension tileSizeOnScreen;
    Coordinate tileSizeInWorld;
    Supplier<BufferedImage> missingTileImage;
    CoordinateReferenceSystem mapCRS;
    CoordinateReferenceSystem tileCRS;
    Map<String, String> params;
    private MVTStyles styles;
    private final double resolution;

    public MVTTilesInfo(
            Dimension tileSizeOnScreen,
            double resolution,
            Supplier<BufferedImage> missingTileImage,
            CoordinateReferenceSystem mapCRS,
            CoordinateReferenceSystem tileCRS,
            Map<String, String> params
    ) {
      this.tileSizeOnScreen = tileSizeOnScreen;
      this.resolution = resolution;
      this.tileSizeInWorld =new Coordinate(tileSizeOnScreen.width * resolution, tileSizeOnScreen.height * resolution);        
      this.missingTileImage = missingTileImage;
      this.mapCRS = mapCRS;
      this.tileCRS = tileCRS;
      this.params = params;
    }

    public Coordinate getTileSizeInWorld() {
      return tileSizeInWorld;
    }
    
    public URL getVectorStylesURL() throws MalformedURLException {
      String vs = this.params.get("vectorStyles");
      if(StringUtils.isBlank(vs)) {
        return null;
      } 
      return new URL(vs);
    }

    @SuppressWarnings("UseSpecificCatch")
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

    public MVTTileInfo createTileInfo(Coordinate gridOrigin, int tileIndexX, int tileIndexY) {
      double geoX = gridOrigin.x + (tileIndexX * this.tileSizeInWorld.x);
      double geoY = gridOrigin.y + (tileIndexY * this.tileSizeInWorld.y);
      Envelope tileBounds = new Envelope(
              geoX,
              geoX + this.tileSizeInWorld.x,
              geoY,
              geoY + this.tileSizeInWorld.y
      );

      MVTTileInfo tileInfo = new MVTTileInfo(
              tileBounds,
              tileSizeOnScreen,
              getVectorStyles(),
              missingTileImage,
              mapCRS, tileCRS,
              params
      );
      return tileInfo;
    }
  }
 
  private static class MVTTileInfo {
    private final Envelope tileEnvelope;
    private final Dimension tileSizeOnScreen;
    private final MVTStyles vectorStyles;
    private final Supplier<BufferedImage> missingImage;
    private final CoordinateReferenceSystem mapCRS;
    private final CoordinateReferenceSystem tileCRS;
    private final Map<String, String> params;
    
    public MVTTileInfo(
            Envelope tileEnvelope, 
            Dimension tileSizeOnScreen, 
            MVTStyles vectorStyles, 
            Supplier<BufferedImage> missingImage,
            CoordinateReferenceSystem mapCRS,
            CoordinateReferenceSystem tileCRS,
            Map<String, String> params
      ) {
      this.tileEnvelope = tileEnvelope;
      this.tileSizeOnScreen = tileSizeOnScreen;
      this.vectorStyles = vectorStyles;      
      this.missingImage = missingImage;
      this.mapCRS = mapCRS;
      this.tileCRS = tileCRS;
      this.params = params;
    }

    private BufferedImage getMissingTileImage() {
      if( missingImage == null ) {
        return null;
      }
      return this.missingImage.get();
    }

    private Envelope getTileEnvelope() {
      return this.tileEnvelope;
    }

    private Dimension getTileSizeOnScreen() {
      return this.tileSizeOnScreen;
    }

    private MVTStyles getVectorStyles() {
      return this.vectorStyles;
    }

    public Map<String, String> getParams() {
      return this.params;
    }
    
  }
  
  /** Tile Task. */
  public abstract static class TileTask extends RecursiveTask<Tile> implements Callable<Tile> {
    private final int tileIndexX;
    private final int tileIndexY;

    /**
     * Constructor.
     *
     * @param tileIndexX tile index x
     * @param tileIndexY tile index y
     */
    public TileTask(final int tileIndexX, final int tileIndexY) {
      this.tileIndexX = tileIndexX;
      this.tileIndexY = tileIndexY;
    }

    public final int getTileIndexX() {
      return this.tileIndexX;
    }

    public final int getTileIndexY() {
      return this.tileIndexY;
    }

    @Override
    public final Tile call() {
      return this.compute();
    }
  }

  /** Single Tile Loader Task. */
  public static final class SingleTileLoaderTask extends TileTask {

    private final ClientHttpRequest tileRequest;
    private final boolean failOnError;
    private final MetricRegistry registry;
    private final Processor.ExecutionContext context;
    private final BufferedImage errorImage;
    private MVTTileInfo mvtTileInfo;

    /**
     * Constructor.
     *
     * @param tileRequest tile request
     * @param errorImage error image
     * @param tileIndexX tile index x
     * @param tileIndexY tile index y
     * @param failOnError fail on error
     * @param registry registry
     * @param context the job ID
     */
    public SingleTileLoaderTask(
        final ClientHttpRequest tileRequest,
        final BufferedImage errorImage,
        final int tileIndexX,
        final int tileIndexY,
        final boolean failOnError,
        final MetricRegistry registry,
        final Processor.ExecutionContext context) {
      super(tileIndexX, tileIndexY);
      this.tileRequest = tileRequest;
      this.errorImage = errorImage;
      this.failOnError = failOnError;
      this.registry = registry;
      this.context = context;
    }

    private void setMVTTileInfo(final MVTTileInfo mvtTileInfo) {
      this.mvtTileInfo = mvtTileInfo;
    }
    
    @Override
    protected Tile compute() {
      return this.context.mdcContext(
          () -> {
            final String baseMetricName =
                TilePreparationTask.class.getName()
                    + ".read."
                    + StatsUtils.quotePart(this.tileRequest.getURI().getHost());
            LOGGER.debug("{} -- {}", this.tileRequest.getMethod(), this.tileRequest.getURI());
            try (Timer.Context timerDownload = this.registry.timer(baseMetricName).time()) {
              try (ClientHttpResponse response = this.tileRequest.execute()) {
                final Tile x = handleSpecialStatuses(response, baseMetricName);
                if (x != null) {
                  return x;
                }

                BufferedImage image = getImageFromResponse(response, baseMetricName);
                timerDownload.stop();

                return new Tile(image, getTileIndexX(), getTileIndexY());
              } catch (IOException | RuntimeException e) {
                this.registry.counter(baseMetricName + ".error").inc();
                throw new PrintException("Failed to compute Coverage Task", e);
              }
            }
          });
    }

    private Tile handleSpecialStatuses(
        final ClientHttpResponse response, final String baseMetricName) throws IOException {
      final int httpStatusCode = response.getRawStatusCode();
      if (httpStatusCode == HttpStatus.NO_CONTENT.value()
          || httpStatusCode == HttpStatus.NOT_FOUND.value()) {
        if (httpStatusCode == HttpStatus.NOT_FOUND.value()) {
          LOGGER.info(
              "The request {} returns a not found status code, we consider it as an empty tile.",
              this.tileRequest.getURI());
        }
        // Empty response, nothing special to do
        return new Tile(null, getTileIndexX(), getTileIndexY());
      } else if (httpStatusCode != HttpStatus.OK.value()) {
        return handleNonOkStatus(response, baseMetricName);
      }
      return null;
    }

    private BufferedImage getImageFromResponse(
        final ClientHttpResponse response, final String baseMetricName) throws IOException {
      BufferedImage image = isVectorTile(response) ? renderVectorTile(response) : ImageIO.read(response.getBody());
      if (image == null) {
        if (this.failOnError) {
          this.registry.counter(baseMetricName + ".failOn.error").inc();
          String message =
              String.format(
                  "SingleTileLoader Task stopped since fail on error parameter is enabled and the"
                      + " URL %s is an image format than cannot be decoded",
                  this.tileRequest.getURI());
          LOGGER.error(message);
          throw new PrintException(message);
        }
        LOGGER.warn(
            "The URL: {} is an image format that cannot be decoded", this.tileRequest.getURI());
        image = this.errorImage;
        this.registry.counter(baseMetricName + ".error").inc();
      }
      return image;
    }
    
    private BufferedImage renderVectorTile(final ClientHttpResponse response) throws IOException {
      if(response.getBody() == null || response.getBody() instanceof EmptyInputStream) {
        LOGGER.info("response.body is empty");
        return this.mvtTileInfo.getMissingTileImage();
      }
      if(response.getBody().available() < 1) {
        LOGGER.info("response.body is empty. available = "+response.getBody().available());
        return this.mvtTileInfo.getMissingTileImage();
      }
      MVTStyles styles = this.mvtTileInfo.getVectorStyles();
      if(styles == null) {
        LOGGER.info("vectorStyles is NULL");
        return this.mvtTileInfo.getMissingTileImage();
      }
      MVTTile tile = new MVTTile(this.mvtTileInfo.tileCRS, this.mvtTileInfo.mapCRS);
      tile.setParams(this.mvtTileInfo.getParams());
      tile.download(response.getBody(), mvtTileInfo.getTileEnvelope(), styles.extractFieldsFromStyles());
      Dimension tileSizeOnScreen = this.mvtTileInfo.getTileSizeOnScreen();
      BufferedImage image = tile.render(styles, tileSizeOnScreen.width, tileSizeOnScreen.height);
      return image;
    }
    
    private boolean isVectorTile(final ClientHttpResponse response) {
      if(this.mvtTileInfo == null) {
        return false;
      }
      try {
        MediaType ct = response.getHeaders().getContentType();
        boolean r = ct != null && ct.toString().equalsIgnoreCase("application/x-protobuf");
        if(!r) {
          String s = this.tileRequest.getURI().getPath().toLowerCase();
          r = s.endsWith(".pbf");
        }
        return r;
      } catch (Exception e) {
        LOGGER.info("error ", e);
        return false;
      }

    }

    private Tile handleNonOkStatus(final ClientHttpResponse response, final String baseMetricName)
        throws IOException {
      final int httpStatusCode = response.getRawStatusCode();
      String errorMessage =
          String.format(
              "Error making tile request: %s\n\tStatus: %d\n\tStatus message: %s",
              this.tileRequest.getURI(), httpStatusCode, response.getStatusText());
      LOGGER.debug(
          """
          Error making tile request: {}
          Status: {}
          Status message: {}
          Server:{}
          Body:
          {}""",
          this.tileRequest.getURI(),
          httpStatusCode,
          response.getStatusText(),
          response.getHeaders().getFirst(HttpHeaders.SERVER),
          IOUtils.toString(response.getBody(), StandardCharsets.UTF_8));
      this.registry.counter(baseMetricName + ".error").inc();
      if (this.failOnError) {
        throw new RuntimeException(errorMessage);
      } else {
        LOGGER.info(errorMessage);
        return new Tile(this.errorImage, getTileIndexX(), getTileIndexY());
      }
    }
  }

  /** PlaceHolder Tile Loader Task. */
  public static class PlaceHolderImageTask extends TileTask {

    private final BufferedImage placeholderImage;

    /**
     * Constructor.
     *
     * @param placeholderImage placeholder image
     * @param tileOriginX tile origin x
     * @param tileOriginY tile origin y
     */
    public PlaceHolderImageTask(
        final BufferedImage placeholderImage, final int tileOriginX, final int tileOriginY) {
      super(tileOriginX, tileOriginY);
      this.placeholderImage = placeholderImage;
    }

    @Override
    protected final Tile compute() {
      return new Tile(this.placeholderImage, getTileIndexX(), getTileIndexY());
    }
  }

  /** Tile. */
  public static final class Tile {
    /** The tile image. */
    private final BufferedImage image;

    /** The x index of the image. the x coordinate to draw this tile is xIndex * tileSizeX */
    private final int xIndex;

    /** The y index of the image. the y coordinate to draw this tile is yIndex * tileSizeY */
    private final int yIndex;

    private Tile(final BufferedImage image, final int xIndex, final int yIndex) {
      this.image = image;
      this.xIndex = xIndex;
      this.yIndex = yIndex;
    }

    /**
     * Get image.
     *
     * @return image
     */
    public BufferedImage getImage() {
      return this.image;
    }

    /**
     * Get x index.
     *
     * @return x index
     */
    public int getxIndex() {
      return this.xIndex;
    }

    /**
     * Get y index.
     *
     * @return y index
     */
    public int getyIndex() {
      return this.yIndex;
    }
  }
}
