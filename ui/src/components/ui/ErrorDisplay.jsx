import React from "react";
import { AlertCircle, XCircle } from "lucide-react";

const ErrorDisplay = ({ message, status = null }) => {
  if (!message) return null;

  return (
    <div className="mt-4 p-4 bg-red-50 border-l-4 border-red-500 rounded-r-lg flex items-start gap-3 animate-in fade-in slide-in-from-top-1">
      <div className="shrink-0">
        <XCircle className="h-5 w-5 text-red-500" />
      </div>
      <div className="flex-1">
        <h3 className="text-sm font-bold text-red-800">
          Operation Failed {status && `(Status: ${status})`}
        </h3>
        <p className="text-sm text-red-700 mt-1">{message}</p>
      </div>
    </div>
  );
};

export default ErrorDisplay;
