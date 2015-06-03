
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.hopsworks.rest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.context.RequestScoped;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import se.kth.bbc.fileoperations.FileOperations;
import se.kth.bbc.lims.Constants;
import se.kth.bbc.project.Project;
import se.kth.bbc.project.ProjectFacade;
import se.kth.hopsworks.controller.ResponseMessages;
import se.kth.hopsworks.filters.AllowedRoles;
import se.kth.bbc.lims.StagingManager;
import se.kth.bbc.lims.Utils;
import se.kth.bbc.project.fb.Inode;
import se.kth.bbc.project.fb.InodeFacade;
import se.kth.bbc.project.fb.InodeView;
import se.kth.bbc.upload.HttpUtils;
import se.kth.bbc.upload.ResumableInfo;
import se.kth.bbc.upload.ResumableInfoStorage;
import se.kth.hopsworks.controller.DataSetDTO;

/**
 * @author André<amore@kth.se>
 * @author Ermias<ermiasg@kth.se>
 */
@RequestScoped
@TransactionAttribute(TransactionAttributeType.NEVER)
public class DataSetService {

  private final static Logger logger = Logger.getLogger(DataSetService.class.
          getName());

  @EJB
  private ProjectFacade projectFacade;
  @EJB
  private NoCacheResponse noCacheResponse;
  @EJB
  private FileOperations fileOps;
  @EJB
  private InodeFacade inodes;
  @EJB
  private StagingManager stagingManager;

  private Integer projectId;
  private Project project;
  private String path;

  public DataSetService() {
  }

  public void setProjectId(Integer projectId) {
    this.projectId = projectId;
    this.project = projectFacade.find(projectId);
    String rootDir = Constants.DIR_ROOT;
    String projectPath = File.separator + rootDir + File.separator
            + this.project.getName();
    this.path = projectPath + File.separator
            + Constants.DIR_DATASET + File.separator;
  }

  public Integer getProjectId() {
    return projectId;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
  public Response findDataSetsInProjectID(
          @Context SecurityContext sc,
          @Context HttpServletRequest req) throws AppException {

    Inode projectInode = inodes.findByName(this.project.getName());
    Inode parent = inodes.findByParentAndName(projectInode,
            Constants.DIR_DATASET);
    logger.log(Level.FINE, "findDataSetsInProjectID Parent name: {0}", parent.
            getInodePK().getName());
    List<Inode> cwdChildren;
    cwdChildren = inodes.findByParent(parent);
    List<InodeView> kids = new ArrayList<>();
    for (Inode i : cwdChildren) {
      kids.add(new InodeView(i, inodes.getPath(i)));
      logger.log(Level.FINE, "path: {0}", inodes.getPath(i));
    }

    logger.log(Level.FINE, "Num of children: {0}", cwdChildren.size());
    GenericEntity<List<InodeView>> inodViews
            = new GenericEntity<List<InodeView>>(kids) {
            };

    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
            inodViews).build();
  }

  @GET
  @Path("/{path: .+}")
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.DATA_SCIENTIST, AllowedRoles.DATA_OWNER})
  public Response getDirContent(
          @PathParam("path") String path,
          @Context SecurityContext sc,
          @Context HttpServletRequest req) throws AppException {

    Inode projectInode = inodes.findByName(this.project.getName());
    Inode parent = inodes.findByParentAndName(projectInode,
            Constants.DIR_DATASET);
    String[] pathArray = path.split(File.separator);
    for (String p : pathArray) {
      parent = inodes.findByParentAndName(parent, p);
    }
    List<Inode> cwdChildren;
    cwdChildren = inodes.findByParent(parent);
    List<InodeView> kids = new ArrayList<>();
    for (Inode i : cwdChildren) {
      kids.add(new InodeView(i, inodes.getPath(i)));
      logger.log(Level.FINE, "path: {0}", inodes.getPath(i));
    }

    logger.log(Level.FINE, "Num of children: {0}", cwdChildren.size());
    GenericEntity<List<InodeView>> inodViews
            = new GenericEntity<List<InodeView>>(kids) {
            };

    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
            inodViews).build();
  }

  @GET
  @Path("{fileName}")
  @Produces(MediaType.MULTIPART_FORM_DATA)
  @AllowedRoles(roles = {AllowedRoles.DATA_OWNER})
  public InputStream downloadFile(
          @PathParam("fileName") String fileName,
          @Context SecurityContext sc,
          @Context HttpServletRequest req) throws AppException {
    InputStream is = null;
    String filePath = this.path + fileName;
    try {
      is = fileOps.getInputStream(filePath);
      logger.log(Level.FINE, "File was downloaded from HDFS path: {0}",
              filePath);
    } catch (IOException ex) {
      logger.log(Level.SEVERE, null, ex);
    }
    return is;
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.DATA_OWNER})
  public Response createDataSetDir(
          DataSetDTO dataSetName,
          @Context SecurityContext sc,
          @Context HttpServletRequest req) throws AppException {
    boolean success = false;
    JsonResponse json = new JsonResponse();
    if (dataSetName == null || dataSetName.getName().isEmpty()) {
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              ResponseMessages.DATASET_NAME_EMPTY);
    }

    String dsPath = this.path + dataSetName.getName();
    try {
      success = fileOps.mkDir(dsPath);
    } catch (IOException ex) {
      logger.log(Level.SEVERE, null, ex);
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              "Could not create the directory at " + dsPath);
    }
    if (!success) {
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              "Could not create the directory at " + dsPath);
    }
    json.setSuccessMessage("A directory for the dataset was created at "
            + dsPath);
    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
            json).build();

  }

  @DELETE
  @Path("/{fileName: .+}")
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.DATA_OWNER})
  public Response removedataSetdir(
          @PathParam("fileName") String fileName,
          @Context SecurityContext sc,
          @Context HttpServletRequest req) throws AppException {
    boolean success = false;
    JsonResponse json = new JsonResponse();
    if (fileName == null || fileName.isEmpty()) {
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              ResponseMessages.DATASET_NAME_EMPTY);
    }
    String filePath = this.path + fileName;
    System.err.println(filePath);
    logger.log(Level.INFO, filePath);
    try {
      success = fileOps.rmRecursive(filePath);
    } catch (IOException ex) {
      logger.log(Level.SEVERE, null, ex);
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              "Could not delete the file at " + filePath);
    }
    if (!success) {
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              "Could not delete the file at " + filePath);
    }
    json.setSuccessMessage(ResponseMessages.DATASET_REMOVED_FROM_HDFS);
    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
            json).build();

  }

  @GET
  @Path("upload/{path: .+}")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.APPLICATION_JSON)
  @AllowedRoles(roles = {AllowedRoles.DATA_OWNER})
  public Response uploadFile(
          @PathParam("path") String path,
          @Context HttpServletRequest request)
          throws AppException, IOException {
    JsonResponse json = new JsonResponse();

    String uploadPath = this.path + path + "/";
    logger.log(Level.INFO, "length.....{0}", request.getParameter(
            "flowCurrentChunkSize"));

    int resumableChunkNumber = getResumableChunkNumber(request);

    ResumableInfo info = getResumableInfo(request);

    long content_length;
    //Seek to position
    try (RandomAccessFile raf
            = new RandomAccessFile(info.resumableFilePath, "rw");
            InputStream is = request.getInputStream()) {
      //Seek to position
      raf.seek((resumableChunkNumber - 1) * (long) info.resumableChunkSize);
      //Save to file
      long readed = 0;
      content_length = HttpUtils.toLong(request.getParameter(
              "flowCurrentChunkSize"), -1);
      byte[] bytes = new byte[1024 * 100];
      while (readed < content_length) {
        int r = is.read(bytes);
        if (r < 0) {
          break;
        }
        raf.write(bytes, 0, r);
        readed += r;
      }
    }

    boolean finished = false;

    //Mark as uploaded and check if finished
    if (info.addChuckAndCheckIfFinished(
            new ResumableInfo.ResumableChunkNumber(
                    resumableChunkNumber), content_length)) { //Check if all chunks uploaded, and change filename
      ResumableInfoStorage.getInstance().remove(info);
      logger.log(Level.SEVERE, "All finished.");
      finished = true;
    } else {
      logger.log(Level.SEVERE, "Upload");
    }

    if (finished) {
      try {
        uploadPath = Utils.ensurePathEndsInSlash(uploadPath);
        fileOps.copyAfterUploading(info.resumableFilename, uploadPath
                + info.resumableFilename);
        logger.log(Level.SEVERE, "Copied to HDFS");
      } catch (IOException e) {
        logger.log(Level.SEVERE, "Failed to write to HDSF", e);
      }
    }

    json.setSuccessMessage("Successfuly uploaded file to " + uploadPath);
    return noCacheResponse.getNoCacheResponseBuilder(Response.Status.OK).entity(
            json).build();
  }

  private int getResumableChunkNumber(HttpServletRequest request) {
    return HttpUtils.toInt(request.getParameter("flowChunkNumber"), -1);
  }

  private ResumableInfo getResumableInfo(HttpServletRequest request) throws
          AppException {
    String base_dir = stagingManager.getStagingPath();

    int resumableChunkSize = HttpUtils.toInt(request.getParameter(
            "flowChunkSize"), -1);
    long resumableTotalSize = HttpUtils.toLong(request.getParameter(
            "flowTotalSize"), -1);
    String resumableIdentifier = request.getParameter("flowIdentifier");
    String resumableFilename = request.getParameter("flowFilename");
    String resumableRelativePath = request.getParameter("flowRelativePath");
    //Here we add a ".temp" to every upload file to indicate NON-FINISHED
    String resumableFilePath = new File(base_dir, resumableFilename).
            getAbsolutePath() + ".temp";
    //TODO: the current way of uploading will not scale: if two persons happen to upload a file with the same name, trouble is waiting to happen

    ResumableInfoStorage storage = ResumableInfoStorage.getInstance();

    ResumableInfo info = storage.get(resumableChunkSize, resumableTotalSize,
            resumableIdentifier, resumableFilename, resumableRelativePath,
            resumableFilePath);
    if (!info.vaild()) {
      storage.remove(info);
      throw new AppException(Response.Status.BAD_REQUEST.getStatusCode(),
              "Invalid request params. ");
    }
    return info;
  }

}
