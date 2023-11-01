/**
 * @(#)Result.JAVA, 2020年04月02日.
 * <p>
 * Copyright 2020 GEETEST, Inc. All rights reserved.
 * GEETEST PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.geetest.sdk;

import lombok.Data;

/**
 * sdk lib包的返回结果信息。
 *
 * @author liuquan@geetest.com
 */
@Data
public class GeetestLibResult {
    /**
     * 成功失败的标识码，1表示成功，0表示失败
     */
    private int status = 0;

    /**
     * 返回数据，json格式
     */
    private String data = "";

    /**
     * 备注信息，如异常信息等
     */
    private String msg = "";

    public void setAll(int status, String data, String msg) {
        this.setStatus(status);
        this.setData(data);
        this.setMsg(msg);
    }

    @Override
    public String toString() {
        return String.format("GeetestLibResult{status=%s, data=%s, msg=%s}", this.status, this.data, this.msg);
    }
}
