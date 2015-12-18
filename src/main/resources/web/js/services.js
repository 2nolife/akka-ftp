'use strict';

var services = angular.module('akkaFtp.services', []);

services.factory('dashboardService', function($resource) {
  return $resource('/api/dashboard');
});

services.factory('sessionService', function($resource) {
  var service = {};

  service.sessions = $resource('/api/sessions');
  service.disconnected = $resource('/api/sessions?disconnected');

  service.session = function(id) {
    return $resource('/api/sessions/'+id);
  };

  return service;
});

services.factory('controlService', function($resource) {
  var service = {};

  service.action = function(action) { return $resource('/api/action/'+action); }

  return service;
});
