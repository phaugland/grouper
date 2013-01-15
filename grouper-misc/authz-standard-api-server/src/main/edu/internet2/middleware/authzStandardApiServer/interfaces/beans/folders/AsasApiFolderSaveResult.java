/**
 * 
 */
package edu.internet2.middleware.authzStandardApiServer.interfaces.beans.folders;



/**
 * Save a folder result
 * @author mchyzer
 *
 */
public class AsasApiFolderSaveResult {
  
  /**
   * if this folder was created
   */
  private Boolean created;
  
  /**
   * if this folder was updated
   */
  private Boolean updated;
  
  /**
   * if this folder was created
   * @return the created
   */
  public Boolean getCreated() {
    return this.created;
  }
  
  /**
   * if this folder was created
   * @param created1 the created to set
   */
  public void setCreated(Boolean created1) {
    this.created = created1;
  }
  
  /**
   * if this folder was updated
   * @return the updated
   */
  public Boolean getUpdated() {
    return this.updated;
  }
  
  /**
   * if this folder was updated
   * @param updated the updated to set
   */
  public void setUpdated(Boolean updated) {
    this.updated = updated;
  }

  /**
   * the saved folder
   */
  private AsasApiFolder folder = null;
  
  /**
   * @return the folder
   */
  public AsasApiFolder getFolder() {
    return this.folder;
  }

  /**
   * @param folder1 is the folder
   */
  public void setFolder(AsasApiFolder folder1) {
    this.folder = folder1;
  }
  
  
  
}
