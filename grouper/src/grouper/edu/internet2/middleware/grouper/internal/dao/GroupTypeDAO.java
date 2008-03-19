/*
  Copyright (C) 2007 University Corporation for Advanced Internet Development, Inc.
  Copyright (C) 2007 The University Of Chicago

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package edu.internet2.middleware.grouper.internal.dao;
import  edu.internet2.middleware.grouper.SchemaException;
import  edu.internet2.middleware.grouper.internal.dto.FieldDTO;
import  edu.internet2.middleware.grouper.internal.dto.GroupTypeDTO;
import  java.util.Set;

/** 
 * Basic <code>GroupType</code> DAO interface.
 * <p><b>WARNING: THIS IS AN ALPHA INTERFACE THAT MAY CHANGE AT ANY TIME.</b></p>
 * @author  blair christensen.
 * @version $Id: GroupTypeDAO.java,v 1.3.4.1 2008-03-19 18:46:11 mchyzer Exp $
 * @since   1.2.0
 */
public interface GroupTypeDAO extends GrouperDAO {

  /**
   * hibernate version, int for each insert/update, negative is new
   * @return hibernate version
   */
  long getHibernateVersion();
  
  /**
   * hibernate version, int for each insert/update, negative is new
   * @param theHibernateVersion
   */
  GroupTypeDAO setHibernateVersion(long theHibernateVersion);

  /**
   * @since   1.2.0
   */
  long create(GroupTypeDTO _gt)
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  long createField(FieldDTO _f)
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  void delete(GroupTypeDTO _gt, Set fields)
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  void deleteField(FieldDTO _f) 
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  boolean existsByName(String name)
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  Set findAll() 
    throws  GrouperDAOException;

  /**
   * @since   1.2.0
   */
  GroupTypeDTO findByUuid(String uuid)
    throws  GrouperDAOException,
            SchemaException
            ;

  /**
   * @since   1.2.0
   */
  String getCreatorUuid();

  /**
   * @since   1.2.0
   */
  long getCreateTime();

  /**
   * @since   1.2.0
   */
  Set getFields();

  /**
   * @since   1.2.0
   */
  boolean getIsAssignable();

  /** 
   * @since   1.2.0
   */
  boolean getIsInternal();

  /**
   * @since   1.2.0
   */
  String getName();

  /**
   * @since   1.2.0
   */
  String getUuid();

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setCreatorUuid(String creatorUUID);

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setCreateTime(long createTime);
  
  /**
   * @since   1.2.0
   */
  GroupTypeDAO setFields(Set fields);

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setIsAssignable(boolean isAssignable);

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setIsInternal(boolean isInternal);

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setName(String name);

  /**
   * @since   1.2.0
   */
  GroupTypeDAO setUuid(String uuid);

} 

