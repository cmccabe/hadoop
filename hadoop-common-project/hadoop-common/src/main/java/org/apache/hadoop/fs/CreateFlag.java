/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.AbstractCollection;
import java.util.EnumSet;

import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/****************************************************************
 * CreateFlag specifies the file create semantic. Users can combine flags like: <br>
 * <code>
 * EnumSet.of(CreateFlag.CREATE, CreateFlag.APPEND)
 * <code>
 * <p>
 * 
 * Use the CreateFlag as follows:
 * <ol>
 * <li> CREATE - to create a file if it does not exist, 
 * else throw FileAlreadyExists.</li>
 * <li> APPEND - to append to a file if it exists, 
 * else throw FileNotFoundException.</li>
 * <li> OVERWRITE - to truncate a file if it exists, 
 * else throw FileNotFoundException.</li>
 * <li> CREATE|APPEND - to create a file if it does not exist, 
 * else append to an existing file.</li>
 * <li> CREATE|OVERWRITE - to create a file if it does not exist, 
 * else overwrite an existing file.</li>
 * <li> SYNC_BLOCK - to force closed blocks to the disk device.
 * In addition {@link Syncable#hsync()} should be called after each write,
 * if true synchronous behavior is required.</li>
 * </ol>
 * 
 * Following combination is not valid and will result in 
 * {@link HadoopIllegalArgumentException}:
 * <ol>
 * <li> APPEND|OVERWRITE</li>
 * <li> CREATE|APPEND|OVERWRITE</li>
 * </ol>
 *****************************************************************/
@InterfaceAudience.Public
@InterfaceStability.Evolving
public enum CreateFlag {

  /**
   * Create a file. See javadoc for more description
   * already exists
   */
  CREATE((short) 0x01),

  /**
   * Truncate/overwrite a file. Same as POSIX O_TRUNC. See javadoc for description.
   */
  OVERWRITE((short) 0x02),

  /**
   * Append to a file. See javadoc for more description.
   */
  APPEND((short) 0x04),

  /**
   * Force closed blocks to disk. Similar to POSIX O_SYNC. See javadoc for description.
   */
  SYNC_BLOCK((short) 0x08),

  // TODO: add a new function to FileSystem rather than overloading these
  // flags, probably.
  BANK0((short) 0x10),
  BANK1((short) 0x11),
  BANK2((short) 0x12),
  BANK3((short) 0x13),
  BANK4((short) 0x14),
  BANK5((short) 0x15),
  BANK6((short) 0x16),
  BANK7((short) 0x17),
  BANK8((short) 0x18),
  BANK9((short) 0x19);

  private final short mode;

  private CreateFlag(short mode) {
    this.mode = mode;
  }

  short getMode() {
    return mode;
  }
  
  /**
   * Validate the CreateFlag and throw exception if it is invalid
   * @param flag set of CreateFlag
   * @throws HadoopIllegalArgumentException if the CreateFlag is invalid
   */
  public static void validate(EnumSet<CreateFlag> flag) {
    if (flag == null || flag.isEmpty()) {
      throw new HadoopIllegalArgumentException(flag
          + " does not specify any options");
    }
    final boolean append = flag.contains(APPEND);
    final boolean overwrite = flag.contains(OVERWRITE);
    
    // Both append and overwrite is an error
    if (append && overwrite) {
      throw new HadoopIllegalArgumentException(
          flag + "Both append and overwrite options cannot be enabled.");
    }
  }
  
  /**
   * Validate the CreateFlag for create operation
   * @param path Object representing the path; usually String or {@link Path}
   * @param pathExists pass true if the path exists in the file system
   * @param flag set of CreateFlag
   * @throws IOException on error
   * @throws HadoopIllegalArgumentException if the CreateFlag is invalid
   */
  public static void validate(Object path, boolean pathExists,
      EnumSet<CreateFlag> flag) throws IOException {
    validate(flag);
    final boolean append = flag.contains(APPEND);
    final boolean overwrite = flag.contains(OVERWRITE);
    if (pathExists) {
      if (!(append || overwrite)) {
        throw new FileAlreadyExistsException("File already exists: "
            + path.toString()
            + ". Append or overwrite option must be specified in " + flag);
      }
    } else if (!flag.contains(CREATE)) {
      throw new FileNotFoundException("Non existing file: " + path.toString()
          + ". Create option is not specified in " + flag);
    }
  }

  public final static int DEFAULT_BANK = 0;
  
  public static boolean flagsContainsBank(EnumSet<CreateFlag> flag) {
    if (flag.contains(CreateFlag.BANK0))
      return true;
    if (flag.contains(CreateFlag.BANK1))
      return true;
    if (flag.contains(CreateFlag.BANK2))
      return true;
    if (flag.contains(CreateFlag.BANK3))
      return true;
    if (flag.contains(CreateFlag.BANK4))
      return true;
    if (flag.contains(CreateFlag.BANK5))
      return true;
    if (flag.contains(CreateFlag.BANK6))
      return true;
    if (flag.contains(CreateFlag.BANK7))
      return true;
    if (flag.contains(CreateFlag.BANK8))
      return true;
    if (flag.contains(CreateFlag.BANK9))
      return true;
    return false;
  }

  public static int flagsToBank(AbstractCollection<CreateFlag> flag) {
    int bank = 0;
    if (flag.contains(CreateFlag.BANK0))
      return 0;
    if (flag.contains(CreateFlag.BANK1))
      return 1;
    if (flag.contains(CreateFlag.BANK2))
      return 2;
    if (flag.contains(CreateFlag.BANK3))
      return 3;
    if (flag.contains(CreateFlag.BANK4))
      return 4;
    if (flag.contains(CreateFlag.BANK5))
      return 5;
    if (flag.contains(CreateFlag.BANK6))
      return 6;
    if (flag.contains(CreateFlag.BANK7))
      return 7;
    if (flag.contains(CreateFlag.BANK8))
      return 8;
    if (flag.contains(CreateFlag.BANK9))
      return 9;
    return DEFAULT_BANK;
  }

  public static CreateFlag bankToFlag(int bank) {
    switch (bank) {
      case 0:
        return CreateFlag.BANK0;
      case 1:
        return CreateFlag.BANK1;
      case 2:
        return CreateFlag.BANK2;
      case 3:
        return CreateFlag.BANK3;
      case 4:
        return CreateFlag.BANK4;
      case 5:
        return CreateFlag.BANK5;
      case 6:
        return CreateFlag.BANK6;
      case 7:
        return CreateFlag.BANK7;
      case 8:
        return CreateFlag.BANK8;
      case 9:
        return CreateFlag.BANK9;
      default:
        throw new RuntimeException("invalid bank " + bank);
    }
  }
}