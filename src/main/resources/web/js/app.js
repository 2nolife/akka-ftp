'use strict';

var akkaFtp = angular.module('akkaFtp', [
  'akkaFtp.services',
  'akkaFtp.controllers',
  'ngResource',
  'ngRoute'
]);

akkaFtp.config(function($routeProvider) {
  $routeProvider
    .when('/', { templateUrl: 'parts/dashboard.html', controller: 'dashboardCtrl' })
    .when('/sessions', { templateUrl: 'parts/sessions.html', controller: 'sessionsCtrl' })
    .when('/sessions/:id', { templateUrl: 'parts/session.html', controller: 'sessionCtrl' })
    .when('/disconnected', { templateUrl: 'parts/disconnected.html', controller: 'disconnectedCtrl' })
    .when('/control', { templateUrl: 'parts/control.html', controller: 'controlCtrl' })
    .otherwise({ redirectTo: '/' });
});
