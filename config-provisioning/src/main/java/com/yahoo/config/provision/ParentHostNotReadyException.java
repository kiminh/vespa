// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

/**
 *
 * Exception thrown when trying to activate a node that runs on a host that is not
 * yet ready to run the node.
 * 
 * @author freva
 *
 */
public class ParentHostNotReadyException extends RuntimeException {

    public ParentHostNotReadyException(String message) {
        super(message);
    }

}
