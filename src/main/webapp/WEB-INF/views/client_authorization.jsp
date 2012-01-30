<%@ include file="/WEB-INF/views/include.jsp"
%><%@page contentType="text/html;charset=UTF-8"
%><%--
  ~ Copyright 2012 Janrain, Inc.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~    http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  --%>
<html>
<head>
	<META http-equiv="Content-Type" content="text/html;charset=UTF-8">
	<title>Bus Owner Authentication</title>
</head>
<body>

<form name="client_authorization" action="" method="post">

<p>Client ID: <c:out value="${client_id}" /></p><br />
<p>Redirect URI: <c:out value="${redirect_uri}" /></p><br />
<p>Scope: <c:out value="${scope}" /></p><br />

<input type="hidden" name="auth_key" value="<c:out value="${auth_key}" />" /><br />
<input type="submit" name="deny" value="Deny" /><br />

<input type="submit" name="authorize" value="Authorize" /><br />

</form>

</body>
</html>