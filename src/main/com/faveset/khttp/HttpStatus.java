// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

class HttpStatus {
    public static final int Continue = 100;
    public static final int SwitchingProtocols = 101;

    public static final int OK = 200;
    public static final int Created = 201;
    public static final int Accepted = 202;
    public static final int NonAuthoritativeInfo = 203;
    public static final int NoContent = 204;
    public static final int ResetContent = 205;
    public static final int PartialContent = 206;

    public static final int MultipleChoices = 300;
    public static final int MovedPermanently = 301;
    public static final int Found = 302;
    public static final int SeeOther = 303;
    public static final int NotModified = 304;
    public static final int UseProxy = 305;
    public static final int TemporaryRedirect = 307;

    public static final int BadRequest = 400;
    public static final int Unauthorized = 401;
    public static final int PaymentRequired = 402;
    public static final int Forbidden = 403;
    public static final int NotFound = 404;
    public static final int MethodNotAllowed = 405;
    public static final int NotAcceptable = 406;
    public static final int ProxyAuthRequired = 407;
    public static final int RequestTimeout = 408;
    public static final int Conflict = 409;
    public static final int Gone = 410;
    public static final int LengthRequired = 411;
    public static final int PreconditionFailed = 412;
    public static final int RequestEntityTooLarge = 413;
    public static final int RequestURITooLong = 414;
    public static final int UnsupportedMediaType = 415;
    public static final int RequestedRangeNotSatisfiable = 416;
    public static final int ExpectationFailed = 417;

    public static final int InternalServerError = 500;
    public static final int NotImplemented = 501;
    public static final int BadGateway = 502;
    public static final int ServiceUnavailable = 503;
    public static final int GatewayTimeout = 504;
    public static final int HTTPVersionNotSupported = 505;
}
