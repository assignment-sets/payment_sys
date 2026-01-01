import React, { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import axios from "axios";
import { userSchema } from "../schemas/userSchema";
import Button from "./ui/Button";
import ErrorDisplay from "./ui/ErrorDisplay";

const CreateUserForm = () => {
  const [serverError, setServerError] = useState({ message: "", status: null });
  const [successMessage, setSuccessMessage] = useState("");

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(userSchema),
  });

  const onSubmit = async (data) => {
    // Clear previous feedback
    setServerError({ message: "", status: null });
    setSuccessMessage("");

    try {
      const response = await axios.post(
        "http://localhost:8080/api/users",
        data
      );
      setSuccessMessage(`User created successfully! ID: ${response.data.id}`);
      reset();
    } catch (error) {
      // Direct mapping to your Java GlobalExceptionHandler's "error" field
      setServerError({
        message: error.response?.data?.error || "An unexpected error occurred",
        status: error.response?.status,
      });
    }
  };

  return (
    <div className="max-w-md mx-auto p-6 bg-white rounded-xl shadow-md border border-gray-100">
      <h2 className="text-2xl font-bold mb-6 text-gray-800">
        Register New User
      </h2>

      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700">
            Full Name
          </label>
          <input
            {...register("name")}
            className="mt-1 block w-full px-3 py-2 bg-white border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            placeholder="John Doe"
          />
          {errors.name && (
            <p className="mt-1 text-sm text-red-600 font-medium">
              {errors.name.message}
            </p>
          )}
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">
            Email Address
          </label>
          <input
            {...register("email")}
            className="mt-1 block w-full px-3 py-2 bg-white border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            placeholder="john@example.com"
          />
          {errors.email && (
            <p className="mt-1 text-sm text-red-600 font-medium">
              {errors.email.message}
            </p>
          )}
        </div>

        <Button
          title="Create User"
          type="submit"
          isLoading={isSubmitting}
          className="w-full"
        />
      </form>

      {/* Success View */}
      {successMessage && (
        <div className="mt-4 p-3 bg-green-100 text-green-700 rounded-md font-medium border border-green-200 animate-in fade-in slide-in-from-top-1">
          {successMessage}
        </div>
      )}

      {/* Error View using the specific component */}
      <ErrorDisplay message={serverError.message} status={serverError.status} />
    </div>
  );
};

export default CreateUserForm;
