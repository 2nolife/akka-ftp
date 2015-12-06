'use strict';

var controllers = angular.module('akkaFtp.controllers', []);

controllers.controller('dashboardCtrl', function($scope, $timeout, dashboardService) {
  var promise;

  $scope.getDashboard = function() {
    dashboardService.get(
      function(data) {
        $scope.sessionCount = data.sessionCount;
        $scope.traffic = data.traffic;
        promise = $timeout($scope.getDashboard, 2*1000);
      },
      function() {
        console.error('disconneced');
      }
    )
  };
  $scope.getDashboard();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });

});

controllers.controller('sessionsCtrl', function($scope, $timeout, sessionService) {
  var promise;

  $scope.getSessions = function() {
    sessionService.sessions.get(
        function (data) {
          $scope.sessions = data.sessions;
          promise = $timeout($scope.getSessions, 10*1000);
        },
        function () {
          console.error('disconneced');
        }
    )
  };
  $scope.getSessions();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });
});

controllers.controller('disconnectedCtrl', function($scope, $timeout, sessionService) {
  var promise;

  $scope.getSessions = function() {
    sessionService.disconnected.get(
        function (data) {
          $scope.sessions = data.sessions;
          promise = $timeout($scope.getSessions, 10*1000);
        },
        function () {
          console.error('disconneced');
        }
    )
  };
  $scope.getSessions();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });
});

controllers.controller('controlCtrl', function($scope, controlService) {

  var doAction = function(action) {
    controlService.action(action).get(
        function (data) {
          $scope.message = data.message;
        },
        function () {
          console.error('disconneced');
        }
    )
  };

  $scope.serverShutdown = function() {
    doAction("shutdown");
  };

  $scope.serverStop = function() {
    doAction("stop");
  };

  $scope.serverSuspend = function() {
    doAction("suspend");
  };

  $scope.serverResume = function() {
    doAction("resume");
  };

  doAction("status");

});
