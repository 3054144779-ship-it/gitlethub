package org.openapitools.exception;

/** 表示 Gitlet 错误的通用异常。 对于致命错误，则
 * .getMessage（） 的结果是要打印的错误消息。
 *  @author P. N. Hilfinger
 */
public class GitletException extends RuntimeException {

    /** 一个无消息的GitletException. */
    public GitletException() {
        super();
    }

    /** 一个 GitletException 的 MSG 作为其消息。 */
    public GitletException(String msg) {
        super(msg);
    }

}
