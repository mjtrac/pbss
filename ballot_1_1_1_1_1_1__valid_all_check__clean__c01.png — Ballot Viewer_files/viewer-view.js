<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"/><title>Ballot Viewer — Sign In</title>
<link rel="stylesheet" href="/css/viewer.css"/></head>
<body>
<div class="login-wrap">
  <div class="login-card">
    
    <h1>bSuite Ballot Viewer</h1>
    
    
    <form method="post" action="/viewer/login">
      <input type="hidden" name="_csrf" value="yy9KEdZCY3hmfbukmLBVDtvHQBOLZOf2hp6mqv5r25qpGida-0suIOJyUEtLHIqc-51hOuLwbSqyUdTb4_uUz8xcuPyQLUI4"/>
      <label for="username">Username</label>
      <input type="text" id="username" name="username" autofocus required/>
      <label for="password">Password</label>
      <input type="password" id="password" name="password" required/>
      <button type="submit" class="login-btn">Sign In</button>
    </form>
  </div>
</div>
</body>
</html>
